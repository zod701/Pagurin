// The game process's entry point: the NativeActivity library of Pagurin's APK. It runs one game's own, unchanged nx2android host from outside the APK:
//   1. the game's folder name comes from the intent's game_id extra;
//   2. the host's libraries are loaded from internal storage, where the app put them after checking them;
//   3. the activity's paths and asset manager are pointed at that game, so everything the host derives from them (front_android.c ANativeActivity_onCreate) is per game;
//   4. the host's ANativeActivity_onCreate is called.
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <android/native_activity.h>
#include <dlfcn.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "pagurin", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "pagurin", __VA_ARGS__)

typedef void (*on_create_fn)(ANativeActivity *, void *, size_t);

static void mkdirs(const char *path) {
    char p[1024]; snprintf(p, sizeof p, "%s", path);
    for (char *s = p + 1; *s; s++) if (*s == '/') { *s = 0; mkdir(p, 0770); *s = '/'; }
    mkdir(p, 0770);
}

static int clear_exception(JNIEnv *E, const char *what) {
    if (!(*E)->ExceptionCheck(E)) return 0;
    (*E)->ExceptionClear(E); LOGE("%s: a Java exception", what); return 1;
}

static void finish_activity(ANativeActivity *a) {
    JNIEnv *E = a->env; jclass c = (*E)->GetObjectClass(E, a->clazz);
    (*E)->CallVoidMethod(E, a->clazz, (*E)->GetMethodID(E, c, "finish", "()V")); clear_exception(E, "finish");
}
static void finish_on_resume(ANativeActivity *a) { finish_activity(a); }

// The intent's game_id, or 0. It becomes a path, so plain characters only
static int intent_game(ANativeActivity *a, char out[65]) {
    JNIEnv *E = a->env; jclass ca = (*E)->GetObjectClass(E, a->clazz);
    jobject in = (*E)->CallObjectMethod(E, a->clazz, (*E)->GetMethodID(E, ca, "getIntent", "()Landroid/content/Intent;"));
    if (clear_exception(E, "getIntent") || !in) return 0;
    jclass ci = (*E)->GetObjectClass(E, in); jstring key = (*E)->NewStringUTF(E, "game_id");
    jstring v = (*E)->CallObjectMethod(E, in, (*E)->GetMethodID(E, ci, "getStringExtra", "(Ljava/lang/String;)Ljava/lang/String;"), key);
    if (clear_exception(E, "getStringExtra") || !v) return 0;
    const char *s = (*E)->GetStringUTFChars(E, v, NULL); size_t n = s ? strlen(s) : 0; int ok = n > 0 && n <= 64 && strspn(s, "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_-") == n;
    if (ok) memcpy(out, s, n + 1);
    if (s) (*E)->ReleaseStringUTFChars(E, v, s);
    return ok;
}

// The game's own APK as an asset manager, for the host's gamepad theme (overlay_theme.c reads assets/overlay/ through ANativeActivity.assetManager). Public API only:
// getResourcesForApplication takes an ApplicationInfo whose source paths name the APK file
static AAssetManager *apk_assets(ANativeActivity *a, const char *apk) {
    JNIEnv *E = a->env; jclass ca = (*E)->GetObjectClass(E, a->clazz);
    jobject pm = (*E)->CallObjectMethod(E, a->clazz, (*E)->GetMethodID(E, ca, "getPackageManager", "()Landroid/content/pm/PackageManager;"));
    if (clear_exception(E, "getPackageManager") || !pm) return NULL;
    jclass cp = (*E)->GetObjectClass(E, pm); jstring path = (*E)->NewStringUTF(E, apk);
    jobject pi = (*E)->CallObjectMethod(E, pm, (*E)->GetMethodID(E, cp, "getPackageArchiveInfo", "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;"), path, 0);
    if (clear_exception(E, "getPackageArchiveInfo") || !pi) return NULL;
    jobject ai = (*E)->GetObjectField(E, pi, (*E)->GetFieldID(E, (*E)->GetObjectClass(E, pi), "applicationInfo", "Landroid/content/pm/ApplicationInfo;"));
    if (!ai) return NULL;
    jclass cai = (*E)->GetObjectClass(E, ai);
    (*E)->SetObjectField(E, ai, (*E)->GetFieldID(E, cai, "sourceDir", "Ljava/lang/String;"), path);
    (*E)->SetObjectField(E, ai, (*E)->GetFieldID(E, cai, "publicSourceDir", "Ljava/lang/String;"), path);
    jobject res = (*E)->CallObjectMethod(E, pm, (*E)->GetMethodID(E, cp, "getResourcesForApplication", "(Landroid/content/pm/ApplicationInfo;)Landroid/content/res/Resources;"), ai);
    if (clear_exception(E, "getResourcesForApplication") || !res) return NULL;
    jobject am = (*E)->CallObjectMethod(E, res, (*E)->GetMethodID(E, (*E)->GetObjectClass(E, res), "getAssets", "()Landroid/content/res/AssetManager;"));
    if (clear_exception(E, "getAssets") || !am) return NULL;
    return AAssetManager_fromJava(E, (*E)->NewGlobalRef(E, am));   // kept for the process's life: the host reads through it from its own threads
}

// src over dst when their size or time differs. Read-only once whole: a library the app loads must not be writable (W^X), and a rename replaces it all the same
// The host the app put in internal storage after checking it (Store.stageCode), never the copy on shared storage, which other writers can reach.
// adrenotools' hooks are beside it: the host finds them there (gfx_vk_core.c vk_open_driver)
static on_create_fn load_host(const char *in) {
    char dst[1024]; snprintf(dst, sizeof dst, "%s/host/libdmhost.so", in);
    void *h = dlopen(dst, RTLD_NOW | RTLD_LOCAL);
    if (!h) { LOGE("dlopen %s: %s", dst, dlerror()); return NULL; }
    on_create_fn f = (on_create_fn)dlsym(h, "ANativeActivity_onCreate");
    if (!f) LOGE("%s has no ANativeActivity_onCreate", dst);
    return f;
}

__attribute__((visibility("default"))) void ANativeActivity_onCreate(ANativeActivity *a, void *saved, size_t saved_size) {
    static on_create_fn host; static char *ext, *in; static AAssetManager *assets;   // kept for a second onCreate (a configuration change), which the host handles itself
    if (!host) {
        char id[65], p[1024];
        // Without a game no game runs: finished when resumed, as finish() from onCreate leaves it on the screen
        if (!intent_game(a, id)) { LOGI("no game_id: nothing to run"); a->callbacks->onResume = finish_on_resume; return; }
        // One folder of GPU drivers for every game, the one the driver picker fills; the host keeps a value already set (front_android.c)
        snprintf(p, sizeof p, "%s/drivers", a->externalDataPath); mkdirs(p); setenv("DM_VK_DRIVER_ROOT", p, 1);
        snprintf(p, sizeof p, "%s/drivers", a->internalDataPath); mkdirs(p); setenv("DM_VK_DRIVER_CACHE", p, 1);
        snprintf(p, sizeof p, "%s/games/%s", a->externalDataPath, id); ext = strdup(p);
        snprintf(p, sizeof p, "%s/games/%s", a->internalDataPath, id); in = strdup(p); mkdirs(in);
        host = load_host(in);
        if (!host) { a->callbacks->onResume = finish_on_resume; return; }
        snprintf(p, sizeof p, "%s/game.apk", ext); assets = apk_assets(a, p);
        if (!assets) LOGE("%s gives no assets: the host draws the gamepad without its theme", p);
        LOGI("running %s: files %s, internal %s", id, ext, in);
    }
    a->externalDataPath = ext; a->internalDataPath = in;
    if (assets) a->assetManager = assets;
    host(a, saved, saved_size);
}
