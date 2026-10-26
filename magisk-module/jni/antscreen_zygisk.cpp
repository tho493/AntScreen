#include "zygisk.hpp"
#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <string>
#include <vector>
#include <cstring>

#define LOG_TAG "AntScreen_Zygisk"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static const char *TARGET_GEARHEAD = "gearhead";
static const char *TARGET_SYSTEM_SERVER = "system_server";
static const char *ENTRY_CLASS_NAME = "com/tho493/antscreen/engine/MirrorPayload";
static const char *ENTRY_METHOD_NAME = "init";
static const char *ENTRY_METHOD_SIG = "()V";

static JavaVM *g_vm = nullptr;

#include <dlfcn.h>

static jboolean nativeLoadPine(JNIEnv *env, jclass, jstring pathStr) {
    JNIEnv *curEnv = env;
    if (!curEnv && g_vm) {
        g_vm->GetEnv((void**)&curEnv, JNI_VERSION_1_6);
    }
    if (curEnv && curEnv->ExceptionCheck()) {
        curEnv->ExceptionClear();
    }

    const char *path = pathStr && curEnv ? curEnv->GetStringUTFChars(pathStr, nullptr) : nullptr;
    const char *targetPath = (path && strlen(path) > 0) ? path : "/system/lib64/libpine.so";
    void *h = dlopen(targetPath, RTLD_NOW | RTLD_GLOBAL);
    if (!h) {
        h = dlopen("/data/adb/modules/antscreen/system/lib64/libpine.so", RTLD_NOW | RTLD_GLOBAL);
    }
    if (path && curEnv) curEnv->ReleaseStringUTFChars(pathStr, path);
    if (!h) {
        LOGE("nativeLoadPine failed to dlopen: %s", dlerror());
        return JNI_FALSE;
    }
    typedef jint (*JNI_OnLoad_t)(JavaVM *, void *);
    JNI_OnLoad_t onLoad = (JNI_OnLoad_t) dlsym(h, "JNI_OnLoad");
    if (onLoad && g_vm) {
        onLoad(g_vm, nullptr);
    }
    if (curEnv && curEnv->ExceptionCheck()) {
        curEnv->ExceptionClear();
    }
    LOGI("nativeLoadPine succeeded for %s!", targetPath);
    return JNI_TRUE;
}

static void companionHandler(int client_fd) {
    const char *paths[] = {
        "/data/adb/modules/antscreen/payload.dexblob",
        "/data/user_de/0/com.tho493.antscreen/files/payload.dexblob",
        "/data/local/tmp/payload.dexblob"
    };

    int fileFd = -1;
    for (const char *path : paths) {
        fileFd = open(path, O_RDONLY);
        if (fileFd >= 0) break;
    }

    if (fileFd < 0) {
        uint32_t zero = 0;
        write(client_fd, &zero, sizeof(zero));
        return;
    }

    struct stat st;
    if (fstat(fileFd, &st) != 0 || st.st_size <= 0) {
        close(fileFd);
        uint32_t zero = 0;
        write(client_fd, &zero, sizeof(zero));
        return;
    }

    uint32_t fileSize = (uint32_t) st.st_size;
    write(client_fd, &fileSize, sizeof(fileSize));

    char buf[16384];
    ssize_t n;
    while ((n = read(fileFd, buf, sizeof(buf))) > 0) {
        ssize_t written = 0;
        while (written < n) {
            ssize_t w = write(client_fd, buf + written, n - written);
            if (w <= 0) {
                close(fileFd);
                return;
            }
            written += w;
        }
    }
    close(fileFd);
}

class AntScreenZygiskModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        this->api = api;
        this->env = env;
        if (env) {
            env->GetJavaVM(&g_vm);
        }
        LOGI("onLoad in PID %d", getpid());
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        if (args && args->nice_name) {
            const char *procName = env->GetStringUTFChars(args->nice_name, nullptr);
            if (procName) {
                if (strstr(procName, TARGET_GEARHEAD)) {
                    LOGI("Target Android Auto detected via nice_name: [%s]", procName);
                    is_target_app = true;
                    target_proc_name = procName;
                    cachedPayload = readPayload();
                }
                env->ReleaseStringUTFChars(args->nice_name, procName);
            }
        }

        if (!is_target_app && args && args->app_data_dir) {
            const char *dir = env->GetStringUTFChars(args->app_data_dir, nullptr);
            if (dir) {
                if (strstr(dir, TARGET_GEARHEAD)) {
                    LOGI("Target Android Auto detected via app_data_dir: [%s]", dir);
                    is_target_app = true;
                    target_proc_name = "com.google.android.projection.gearhead";
                    cachedPayload = readPayload();
                }
                env->ReleaseStringUTFChars(args->app_data_dir, dir);
            }
        }
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        if (!is_target_app) {
            char cmdline[256] = {0};
            int fd = open("/proc/self/cmdline", O_RDONLY);
            if (fd >= 0) {
                read(fd, cmdline, sizeof(cmdline) - 1);
                close(fd);
            }
            if (strstr(cmdline, TARGET_GEARHEAD)) {
                LOGI("Target Android Auto detected in postAppSpecialize via cmdline: [%s]", cmdline);
                is_target_app = true;
                target_proc_name = cmdline;
            }
        }

        if (is_target_app) {
            LOGI("Executing postAppSpecialize injection for Android Auto!");
            injectPayload(env);
        }
    }

    void preServerSpecialize(zygisk::ServerSpecializeArgs *) override {
        LOGI("preServerSpecialize for system_server in PID %d", getpid());
        is_system_server = true;
        target_proc_name = TARGET_SYSTEM_SERVER;
        cachedPayload = readPayload();
    }

    void postServerSpecialize(const zygisk::ServerSpecializeArgs *) override {
        LOGI("Executing postServerSpecialize injection for system_server!");
        injectPayload(env);
    }

private:
    zygisk::Api *api = nullptr;
    JNIEnv *env = nullptr;
    bool is_target_app = false;
    bool is_system_server = false;
    bool already_injected = false;
    std::string target_proc_name;
    std::vector<uint8_t> cachedPayload;

    std::vector<uint8_t> readPayload() {
        std::vector<uint8_t> buffer;
        const char *paths[] = {
            "/data/adb/modules/antscreen/payload.dexblob",
            "/data/user_de/0/com.tho493.antscreen/files/payload.dexblob",
            "/data/local/tmp/payload.dexblob"
        };

        // 1. Direct read (works in Zygote and system_server)
        for (const char *path : paths) {
            int fd = open(path, O_RDONLY);
            if (fd >= 0) {
                struct stat st;
                if (fstat(fd, &st) == 0 && st.st_size > 0) {
                    buffer.resize(st.st_size);
                    ssize_t bytesRead = read(fd, buffer.data(), st.st_size);
                    close(fd);
                    if (bytesRead == st.st_size) {
                        LOGI("Read payload %zu bytes directly from %s", buffer.size(), path);
                        return buffer;
                    }
                }
                close(fd);
            }
        }

        // 2. Companion read via IPC socket
        if (api) {
            int sock = api->connectCompanion();
            if (sock >= 0) {
                uint32_t size = 0;
                if (read(sock, &size, sizeof(size)) == sizeof(size) && size > 0 && size < 50 * 1024 * 1024) {
                    buffer.resize(size);
                    size_t total = 0;
                    while (total < size) {
                        ssize_t r = read(sock, buffer.data() + total, size - total);
                        if (r <= 0) break;
                        total += r;
                    }
                    close(sock);
                    if (total == size) {
                        LOGI("Read payload %zu bytes via Zygisk companion", total);
                        return buffer;
                    }
                }
                close(sock);
            }
        }

        LOGE("Failed to read payload from direct files or companion service");
        return buffer;
    }

    void injectPayload(JNIEnv *targetEnv) {
        if (!targetEnv || already_injected) return;
        already_injected = true;
        if (targetEnv->ExceptionCheck()) {
            targetEnv->ExceptionClear();
        }

        if (cachedPayload.empty()) {
            cachedPayload = readPayload();
        }
        if (cachedPayload.empty()) {
            LOGE("Payload buffer is empty, injection aborted");
            return;
        }

        const uint8_t *dexPtr = cachedPayload.data();
        size_t dexLen = cachedPayload.size();

        std::vector<std::pair<const uint8_t*, size_t>> slices;
        if (dexLen > 8 && memcmp(dexPtr, "dex\n", 4) != 0) {
            uint32_t count = (dexPtr[0] << 24) | (dexPtr[1] << 16) | (dexPtr[2] << 8) | dexPtr[3];
            if (count > 0 && count < 64 && dexLen > (4 + count * 4)) {
                size_t currentOffset = 4 + count * 4;
                bool valid = true;
                for (uint32_t i = 0; i < count; ++i) {
                    uint32_t sliceLen = (dexPtr[4 + i * 4] << 24) |
                                        (dexPtr[4 + i * 4 + 1] << 16) |
                                        (dexPtr[4 + i * 4 + 2] << 8) |
                                        (dexPtr[4 + i * 4 + 3]);
                    if (currentOffset + sliceLen > dexLen || memcmp(dexPtr + currentOffset, "dex\n", 4) != 0) {
                        valid = false;
                        LOGE("Slice %u invalid at offset %zu, len %u", i, currentOffset, sliceLen);
                        break;
                    }
                    slices.emplace_back(dexPtr + currentOffset, sliceLen);
                    currentOffset += sliceLen;
                }
                if (valid && !slices.empty()) {
                    LOGI("Parsed dexblob container with %zu DEX slices successfully", slices.size());
                } else {
                    slices.clear();
                }
            }
        }

        if (slices.empty()) {
            if (dexLen >= 4 && memcmp(dexPtr, "dex\n", 4) == 0) {
                slices.emplace_back(dexPtr, dexLen);
                LOGI("Single raw DEX detected of %zu bytes", dexLen);
            } else {
                LOGE("Header does not contain valid DEX format");
                return;
            }
        }

        jclass byteBufferClass = targetEnv->FindClass("java/nio/ByteBuffer");
        if (!byteBufferClass) {
            LOGE("Failed to find ByteBuffer class");
            targetEnv->ExceptionClear();
            return;
        }

        jobjectArray bufferArray = targetEnv->NewObjectArray((jsize) slices.size(), byteBufferClass, nullptr);
        for (size_t i = 0; i < slices.size(); ++i) {
            jobject bb = targetEnv->NewDirectByteBuffer((void *) slices[i].first, (jlong) slices[i].second);
            targetEnv->SetObjectArrayElement(bufferArray, (jsize) i, bb);
            targetEnv->DeleteLocalRef(bb);
        }

        jclass classLoaderClass = targetEnv->FindClass("java/lang/ClassLoader");
        jmethodID getSystemClassLoader = targetEnv->GetStaticMethodID(classLoaderClass, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
        jobject systemClassLoader = targetEnv->CallStaticObjectMethod(classLoaderClass, getSystemClassLoader);

        jclass inMemoryDexClassLoaderClass = targetEnv->FindClass("dalvik/system/InMemoryDexClassLoader");
        if (!inMemoryDexClassLoaderClass) {
            LOGE("Failed to find InMemoryDexClassLoader");
            targetEnv->ExceptionClear();
            return;
        }

        jobject dexClassLoader = nullptr;
        jmethodID arrayInitMethod = targetEnv->GetMethodID(inMemoryDexClassLoaderClass, "<init>", "([Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
        if (targetEnv->ExceptionCheck()) {
            targetEnv->ExceptionClear();
            arrayInitMethod = nullptr;
        }

        if (arrayInitMethod) {
            LOGI("Using InMemoryDexClassLoader([Ljava/nio/ByteBuffer;ClassLoader)");
            dexClassLoader = targetEnv->NewObject(inMemoryDexClassLoaderClass, arrayInitMethod, bufferArray, systemClassLoader);
        } else {
            jmethodID array3InitMethod = targetEnv->GetMethodID(inMemoryDexClassLoaderClass, "<init>", "([Ljava/nio/ByteBuffer;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
            if (targetEnv->ExceptionCheck()) {
                targetEnv->ExceptionClear();
                array3InitMethod = nullptr;
            }
            if (array3InitMethod) {
                LOGI("Using InMemoryDexClassLoader([Ljava/nio/ByteBuffer;String;ClassLoader)");
                dexClassLoader = targetEnv->NewObject(inMemoryDexClassLoaderClass, array3InitMethod, bufferArray, nullptr, systemClassLoader);
            } else {
                LOGI("Falling back to single ByteBuffer constructor...");
                jobject firstBb = targetEnv->GetObjectArrayElement(bufferArray, 0);
                jmethodID singleInitMethod = targetEnv->GetMethodID(inMemoryDexClassLoaderClass, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
                if (targetEnv->ExceptionCheck()) {
                    targetEnv->ExceptionClear();
                    singleInitMethod = nullptr;
                }
                if (singleInitMethod) {
                    dexClassLoader = targetEnv->NewObject(inMemoryDexClassLoaderClass, singleInitMethod, firstBb, systemClassLoader);
                }
                targetEnv->DeleteLocalRef(firstBb);
            }
        }

        if (targetEnv->ExceptionCheck()) {
            targetEnv->ExceptionDescribe();
            targetEnv->ExceptionClear();
        }

        if (!dexClassLoader || targetEnv->ExceptionCheck()) {
            LOGE("Failed to instantiate InMemoryDexClassLoader");
            targetEnv->ExceptionDescribe();
            targetEnv->ExceptionClear();
            return;
        }

        jmethodID loadClassMethod = targetEnv->GetMethodID(inMemoryDexClassLoaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        jstring entryClassNameStr = targetEnv->NewStringUTF(ENTRY_CLASS_NAME);
        jclass entryClass = (jclass) targetEnv->CallObjectMethod(dexClassLoader, loadClassMethod, entryClassNameStr);
        if (!entryClass || targetEnv->ExceptionCheck()) {
            LOGE("Failed to load %s", ENTRY_CLASS_NAME);
            targetEnv->ExceptionDescribe();
            targetEnv->ExceptionClear();
            return;
        }

        JNINativeMethod nm[] = {
            {(char*)"nativeLoadPine", (char*)"(Ljava/lang/String;)Z", (void*)nativeLoadPine}
        };
        targetEnv->RegisterNatives(entryClass, nm, 1);
        if (targetEnv->ExceptionCheck()) {
            targetEnv->ExceptionClear();
        }

        jmethodID entryMethodStr = targetEnv->GetStaticMethodID(entryClass, ENTRY_METHOD_NAME, "(Ljava/lang/String;)V");
        if (entryMethodStr) {
            jstring pStr = targetEnv->NewStringUTF(target_proc_name.c_str());
            LOGI("Invoking %s.%s(\"%s\") via JNI in PID %d...", ENTRY_CLASS_NAME, ENTRY_METHOD_NAME, target_proc_name.c_str(), getpid());
            targetEnv->CallStaticVoidMethod(entryClass, entryMethodStr, pStr);
        } else {
            targetEnv->ExceptionClear();
            jmethodID entryMethod = targetEnv->GetStaticMethodID(entryClass, ENTRY_METHOD_NAME, "()V");
            if (entryMethod) {
                LOGI("Invoking %s.%s() via JNI in PID %d...", ENTRY_CLASS_NAME, ENTRY_METHOD_NAME, getpid());
                targetEnv->CallStaticVoidMethod(entryClass, entryMethod);
            }
        }

        if (targetEnv->ExceptionCheck()) {
            LOGE("Exception occurred during %s.%s", ENTRY_CLASS_NAME, ENTRY_METHOD_NAME);
            targetEnv->ExceptionDescribe();
            targetEnv->ExceptionClear();
        } else {
            LOGI("SUCCESS: AntScreen Payload initialized successfully in PID %d!", getpid());
        }
    }
};

REGISTER_ZYGISK_MODULE(AntScreenZygiskModule)
REGISTER_ZYGISK_COMPANION(companionHandler)
