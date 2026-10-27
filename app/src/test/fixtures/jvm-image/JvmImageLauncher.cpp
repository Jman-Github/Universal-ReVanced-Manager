#include <jni.h>
#include <dlfcn.h>
#include <cstdio>
#include <string>
#include <vector>

// Exercise JNI embedding without LD_LIBRARY_PATH, as the Android app does.
int main(int argc, char** argv) {
    if (argc < 4) return 2;
    std::string home = argv[1];
    void* vmLibrary = dlopen((home + "/lib/server/libjvm.so").c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!vmLibrary) { fprintf(stderr, "%s\n", dlerror()); return 3; }
    for (int i = 3; i < argc; ++i) {
        if (!dlopen((home + "/lib/" + argv[i]).c_str(), RTLD_NOW | RTLD_GLOBAL)) {
            fprintf(stderr, "%s\n", dlerror()); return 4;
        }
    }
    auto create = reinterpret_cast<jint (*)(JavaVM**, void**, void*)>(dlsym(vmLibrary, "JNI_CreateJavaVM"));
    if (!create) return 5;
    std::string classpath = std::string("-Djava.class.path=") + argv[2];
    JavaVMOption options[] = {{const_cast<char*>(classpath.c_str()), nullptr},
                             {const_cast<char*>("-Djava.awt.headless=true"), nullptr}};
    JavaVMInitArgs args{};
    args.version = JNI_VERSION_1_6;
    args.nOptions = 2;
    args.options = options;
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    if (create(&vm, reinterpret_cast<void**>(&env), &args) != JNI_OK) return 6;
    jclass probe = env->FindClass("ImageProbe");
    if (!probe) { env->ExceptionDescribe(); return 7; }
    jmethodID main = env->GetStaticMethodID(probe, "main", "([Ljava/lang/String;)V");
    if (!main) { env->ExceptionDescribe(); return 8; }
    env->CallStaticVoidMethod(probe, main, env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr));
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); return 9; }
    return vm->DestroyJavaVM() == JNI_OK ? 0 : 10;
}
