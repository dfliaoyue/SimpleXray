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
 * Xray is started with "-confdir <conf_dir>" so it picks up multiple JSON
 * config fragments from that directory.  A stdout pipe is created so the caller
 * can read Xray log output; no stdin pipe is needed because the config is read
 * from files rather than from stdin.
 *
 * JNI signature:
 *   int[] TProxyService.nativeSpawnXray(
 *       String xrayPath, String assetDir, int vpnFd, String confDir)
 *
 * Returns int[2] = { pid, stdout_read_fd } on success, or null on failure.
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
 * This also closes the original vpn_fd position once it has been dup2'd to
 * CHILD_TUN_FD, so no fd leaks occur.
 */
static void close_extra_fds(int keep_fd)
{
    /* 256 open fds is far more than a freshly-forked Android child will have. */
    static const int MAX_FDS = 256;
    int   *fds = (int *)malloc((size_t)MAX_FDS * sizeof(int));
    int    n   = 0;
    DIR   *dir = opendir("/proc/self/fd");

    if (!fds) {
        if (dir) closedir(dir);
        return;
    }

    if (!dir) {
        /* Fallback: brute-force close a reasonable range. */
        long max = sysconf(_SC_OPEN_MAX);
        if (max <= 0 || max > 65536) max = 1024;
        for (int i = 3; i < (int)max; i++) {
            if (i != keep_fd) close(i);
        }
        free(fds);
        return;
    }

    int dir_fd = dirfd(dir);
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL && n < MAX_FDS) {
        if (entry->d_name[0] < '0' || entry->d_name[0] > '9') continue;
        int fd = atoi(entry->d_name);
        if (fd > 2 && fd != keep_fd && fd != dir_fd)
            fds[n++] = fd;
    }
    closedir(dir);

    for (int i = 0; i < n; i++) close(fds[i]);
    free(fds);
}

JNIEXPORT jintArray JNICALL
Java_com_simplexray_an_service_TProxyService_nativeSpawnXray(
        JNIEnv *env, jclass clazz,
        jstring xray_path_j,
        jstring asset_dir_j,
        jint    vpn_fd,
        jstring conf_dir_j)
{
    const char *xray_path = (*env)->GetStringUTFChars(env, xray_path_j, NULL);
    const char *asset_dir = (*env)->GetStringUTFChars(env, asset_dir_j,  NULL);
    const char *conf_dir  = (*env)->GetStringUTFChars(env, conf_dir_j,   NULL);

    /* stdout pipe: child writes logs → parent reads  (stdout_pipe[0] = read end) */
    int stdout_pipe[2] = {-1, -1};

    if (pipe(stdout_pipe) < 0) {
        LOGE("pipe() failed: %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, xray_path_j, xray_path);
        (*env)->ReleaseStringUTFChars(env, asset_dir_j,  asset_dir);
        (*env)->ReleaseStringUTFChars(env, conf_dir_j,   conf_dir);
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
        close(stdout_pipe[0]); close(stdout_pipe[1]);
        (*env)->ReleaseStringUTFChars(env, xray_path_j, xray_path);
        (*env)->ReleaseStringUTFChars(env, asset_dir_j,  asset_dir);
        (*env)->ReleaseStringUTFChars(env, conf_dir_j,   conf_dir);
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

    /* argv: xray_path -confdir <conf_dir>
     * Xray loads all *.json files from the conf_dir in alphabetical order. */
    char *argv[] = { (char *)xray_path, "-confdir", (char *)conf_dir, NULL };

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork() failed: %s", strerror(errno));
        free(new_env);
        close(stdout_pipe[0]); close(stdout_pipe[1]);
        (*env)->ReleaseStringUTFChars(env, xray_path_j, xray_path);
        (*env)->ReleaseStringUTFChars(env, asset_dir_j,  asset_dir);
        (*env)->ReleaseStringUTFChars(env, conf_dir_j,   conf_dir);
        return NULL;
    }

    if (pid == 0) {
        /* ===== CHILD PROCESS ===== */

        /* Send SIGKILL to this process if the parent dies. */
        prctl(PR_SET_PDEATHSIG, SIGKILL);

        /* stdout + stderr → write end of stdout pipe */
        dup2(stdout_pipe[1], STDOUT_FILENO);
        dup2(stdout_pipe[1], STDERR_FILENO);

        /* Close the original pipe ends (already dup2'd to 1/2). */
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

    /* Close the end that belongs to the child. */
    close(stdout_pipe[1]);  /* child writes to this;  parent must not keep it */

    (*env)->ReleaseStringUTFChars(env, xray_path_j, xray_path);
    (*env)->ReleaseStringUTFChars(env, asset_dir_j,  asset_dir);
    (*env)->ReleaseStringUTFChars(env, conf_dir_j,   conf_dir);

    LOGI("Spawned xray pid=%d stdout_read_fd=%d", pid, stdout_pipe[0]);

    /* Return { pid, stdout_read_fd } */
    jintArray result = (*env)->NewIntArray(env, 2);
    if (!result) {
        close(stdout_pipe[0]);
        return NULL;
    }
    jint arr[2] = { (jint)pid, (jint)stdout_pipe[0] };
    (*env)->SetIntArrayRegion(env, result, 0, 2, arr);
    return result;
}
