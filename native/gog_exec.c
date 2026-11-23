// Runs a program (innoextract, packaged as libinnoextract.so) with some open files handed to it
// at fixed descriptor numbers, for the launcher's GOG installer import.
//
// The installer's files come from Android's file picker as open descriptors: the app may read
// them, but not open their paths. The program reads them as /proc/self/fd/<n>, through links
// named like the installer's files (so it finds the .bin parts beside the .exe). Java's
// ProcessBuilder closes every descriptor but 0-2 in the child, hence this.

#define _GNU_SOURCE  // pipe2
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

#define FIRST_FD 10
#define MAX_FDS 32

// int[]{pid, fd of the program's output (stdout and stderr)}, or null.
JNIEXPORT jintArray JNICALL
Java_io_github_cricastrocoli_openmohaa_GogInstaller_spawn(JNIEnv *env, jclass cls, jobjectArray jargv,
                                                         jintArray jfds) {
    (void)cls;
    int argc = (*env)->GetArrayLength(env, jargv);
    int nfds = (*env)->GetArrayLength(env, jfds);
    if (argc < 1 || nfds > MAX_FDS) return NULL;

    char **argv = calloc((size_t)argc + 1, sizeof(char *));
    if (!argv) return NULL;
    for (int i = 0; i < argc; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, jargv, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i] = strdup(c);
        (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
    }

    // Copies of the files above the target numbers, so moving them into place can't clash.
    int fds[MAX_FDS], high[MAX_FDS];
    (*env)->GetIntArrayRegion(env, jfds, 0, nfds, fds);
    for (int i = 0; i < nfds; i++) high[i] = fcntl(fds[i], F_DUPFD_CLOEXEC, 200);

    int out[2];
    pid_t pid = -1;
    if (pipe2(out, O_CLOEXEC) == 0) {
        pid = fork();
        if (pid == 0) {
            // Child: only async-signal-safe calls until exec.
            dup2(out[1], 1);
            dup2(out[1], 2);
            for (int i = 0; i < nfds; i++) dup2(high[i], FIRST_FD + i);  // dup2 clears close-on-exec
            int devnull = open("/dev/null", O_RDONLY);
            if (devnull >= 0) dup2(devnull, 0);
            execv(argv[0], argv);
            _exit(127);
        }
        close(out[1]);
        if (pid < 0) close(out[0]);
    }
    for (int i = 0; i < nfds; i++) if (high[i] >= 0) close(high[i]);
    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    if (pid < 0) return NULL;

    jintArray r = (*env)->NewIntArray(env, 2);
    jint v[2] = {pid, out[0]};
    (*env)->SetIntArrayRegion(env, r, 0, 2, v);
    return r;
}

// Waits for the program; its exit code, or -signal, or -1000 on error.
JNIEXPORT jint JNICALL
Java_io_github_cricastrocoli_openmohaa_GogInstaller_waitFor(JNIEnv *env, jclass cls, jint pid) {
    (void)env; (void)cls;
    int status;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return -1000;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return -1000;
}

JNIEXPORT void JNICALL
Java_io_github_cricastrocoli_openmohaa_GogInstaller_kill(JNIEnv *env, jclass cls, jint pid) {
    (void)env; (void)cls;
    kill(pid, SIGKILL);
}
