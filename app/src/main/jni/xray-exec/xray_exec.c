/*
 * xray_exec.c – spawn the Xray binary with the Android VPN fd properly inherited.
 *
 * Problem: Android's ProcessBuilder (fork + exec) honours FD_CLOEXEC.  The fd
 * returned by VpnService.Builder.establish() always has FD_CLOEXEC set, so it
 * is automatically closed before the child process starts.  Passing its number
 * via the XRAY_TUN_FD environment variable therefore gives Xray an invalid fd.
 *
 * Fix: fork() here in native code, then dup2() the VPN fd to a fixed target fd
 * (CHILD_TUN_FD = 4) *before* exec().  dup2() does not copy FD_CLOEXEC, so fd
 * 4 survives exec and is visible to Xray as its TUN fd.
 *
 * The function also creates stdin / stdout pipes so that the caller can write
 * the Xray config JSON to stdin and read Xray log output from stdout, exactly
 * mirroring the existing ProcessBuilder-based code path.
 *
 * JNI signature:
 *   int[] TProxyService.nativeSpawnXray(
 *       String xrayPath, String assetDir, int vpnFd)
 *
 * Returns int[3] = { pid, stdout_read_fd, stdin_write_fd } on success, or null
 * on failure.
 */

#include <jni.h>

#include <android/log.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <unistd.h>

#define LOG_TAG    "XrayExec"
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* The fd number that will hold the VPN fd inside the Xray process. */
#define CHILD_TUN_FD 4

/*
 * Close every open fd > 2 except keep_fd.  We enumerate /proc/self/fd so that
 * we do not have to guess the upper bound, and we collect all candidates before
 * closing any of them to avoid invalidating the directory stream mid-walk.
 */
static void close_extra_fds(int keep_fd)
{
    int    fds[4096];
    int    n   = 0;
    DIR   *dir = opendir("/proc/self/fd");

    if (!dir) {
        /* Fallback: brute-force close a reasonable range. */
        long max = sysconf(_SC_OPEN_MAX);
        if (max <= 0 || max > 65536) max = 1024;
        for (int i = 3; i < (int)max; i++) {
            if (i != keep_fd) close(i);
        }
        return;
    }

    int dir_fd = dirfd(dir);
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL && n < 4096) {
        if (entry->d_name[0] < '0' || entry->d_name[0] > '9') continue;
        int fd = atoi(entry->d_name);
        if (fd > 2 && fd != keep_fd && fd != dir_fd)
            fds[n++] = fd;
    }
    closedir(dir);

    for (int i = 0; i < n; i++) close(fds[i]);
}

JNIEXPORT jintArray JNICALL
Java_com_simplexray_an_service_TProxyService_nativeSpawnXray(
        JNIEnv *env, jclass clazz,
        jstring xray_path_j,
        jstring asset_dir_j,
        jint    vpn_fd)
{
    const char *xray_path = (*env)->GetStringUTFChars(env, xray_path_j, NULL);
    const char *asset_dir = (*env)->GetStringUTFChars(env, asset_dir_j,  NULL);

    /* stdin pipe:  parent writes config → child reads  (stdin_pipe[0] = read end) */
    /* stdout pipe: child writes logs   → parent reads  (stdout_pipe[0] = read end) */
    int stdin_pipe[2]  = {-1, -1};
    int stdout_pipe[2] = {-1, -1};

    if (pipe(stdin_pipe) < 0 || pipe(stdout_pipe) < 0) {
        LOGE("pipe() failed: %s", strerror(errno));
        if (stdin_pipe[0]  >= 0) { close(stdin_pipe[0]);  close(stdin_pipe[1]);  }
        if (stdout_pipe[0] >= 0) { close(stdout_pipe[0]); close(stdout_pipe[1]); }
        (*env)->ReleaseStringUTFChars(env, xray_path_j, xray_path);
        (*env)->ReleaseStringUTFChars(env, asset_dir_j,  asset_dir);
        return NULL;
    }

    /* Build the environment that will be given to Xray. */
    char asset_env[4096];
    char tun_fd_env[64];
    snprintf(asset_env,   sizeof(asset_env),   "XRAY_LOCATION_ASSET=%s", asset_dir);
    snprintf(tun_fd_env,  sizeof(tun_fd_env),  "XRAY_TUN_FD=%d",         CHILD_TUN_FD);

    /* Inherit parent environment, overriding / appending the two XRAY_* vars. */
    extern char **environ;
    int parent_ec = 0;
    while (environ[parent_ec]) parent_ec++;

    char **new_env = (char **)malloc((size_t)(parent_ec + 3) * sizeof(char *));
    if (!new_env) {
        LOGE("malloc failed for new_env");
        close(stdin_pipe[0]);  close(stdin_pipe[1]);
        close(stdout_pipe[0]); close(stdout_pipe[1]);
        (*env)->ReleaseStringUTFChars(env, xray_path_j, xray_path);
        (*env)->ReleaseStringUTFChars(env, asset_dir_j,  asset_dir);
        return NULL;
    }
    int ni = 0;
    for (int i = 0; i < parent_ec; i++) {
        if (strncmp(environ[i], "XRAY_LOCATION_ASSET=", 20) == 0) continue;
        if (strncmp(environ[i], "XRAY_TUN_FD=",         12) == 0) continue;
        new_env[ni++] = environ[i];
    }
    new_env[ni++] = asset_env;
    new_env[ni++] = tun_fd_env;
    new_env[ni]   = NULL;

    /* argv: just the binary path – Xray reads config from stdin when no -config
     * argument is given, matching the existing ProcessBuilder code path. */
    char *argv[] = { (char *)xray_path, NULL };

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork() failed: %s", strerror(errno));
        free(new_env);
        close(stdin_pipe[0]);  close(stdin_pipe[1]);
        close(stdout_pipe[0]); close(stdout_pipe[1]);
        (*env)->ReleaseStringUTFChars(env, xray_path_j, xray_path);
        (*env)->ReleaseStringUTFChars(env, asset_dir_j,  asset_dir);
        return NULL;
    }

    if (pid == 0) {
        /* ===== CHILD PROCESS ===== */

        /* Send SIGKILL to this process if the parent dies. */
        prctl(PR_SET_PDEATHSIG, SIGKILL);

        /* stdin ← read end of stdin pipe */
        dup2(stdin_pipe[0], STDIN_FILENO);

        /* stdout + stderr → write end of stdout pipe */
        dup2(stdout_pipe[1], STDOUT_FILENO);
        dup2(stdout_pipe[1], STDERR_FILENO);

        /* Close the original pipe ends (already dup2'd to 0/1/2). */
        close(stdin_pipe[0]);
        close(stdin_pipe[1]);
        close(stdout_pipe[0]);
        close(stdout_pipe[1]);

        /* Assign the VPN fd to CHILD_TUN_FD (dup2 never sets FD_CLOEXEC). */
        if ((int)vpn_fd >= 0 && (int)vpn_fd != CHILD_TUN_FD) {
            if (dup2((int)vpn_fd, CHILD_TUN_FD) < 0) {
                _exit(1);
            }
        }

        /* Close every other inherited fd, keeping only 0, 1, 2, CHILD_TUN_FD. */
        close_extra_fds(CHILD_TUN_FD);

        execve(xray_path, argv, new_env);
        /* exec failed */
        _exit(1);
    }

    /* ===== PARENT PROCESS ===== */
    free(new_env);

    /* Close the ends that belong to the child. */
    close(stdin_pipe[0]);   /* child reads from this; parent must not keep it */
    close(stdout_pipe[1]);  /* child writes to this;  parent must not keep it */

    (*env)->ReleaseStringUTFChars(env, xray_path_j, xray_path);
    (*env)->ReleaseStringUTFChars(env, asset_dir_j,  asset_dir);

    LOGI("Spawned xray pid=%d stdin_write_fd=%d stdout_read_fd=%d",
         pid, stdin_pipe[1], stdout_pipe[0]);

    /* Return { pid, stdout_read_fd, stdin_write_fd } */
    jintArray result = (*env)->NewIntArray(env, 3);
    if (!result) {
        close(stdin_pipe[1]);
        close(stdout_pipe[0]);
        return NULL;
    }
    jint arr[3] = { (jint)pid, (jint)stdout_pipe[0], (jint)stdin_pipe[1] };
    (*env)->SetIntArrayRegion(env, result, 0, 3, arr);
    return result;
}
