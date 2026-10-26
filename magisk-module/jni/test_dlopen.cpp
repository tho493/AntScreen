#include <dlfcn.h>
#include <stdio.h>

int main() {
    void* h = dlopen("/data/adb/modules/antscreen/zygisk/x86_64.so", RTLD_NOW);
    if (!h) {
        printf("dlopen error: %s\n", dlerror());
        return 1;
    }
    printf("dlopen SUCCESS!\n");
    void* entry = dlsym(h, "zygisk_module_entry");
    printf("zygisk_module_entry symbol: %p\n", entry);
    dlclose(h);
    return 0;
}
