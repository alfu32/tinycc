#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "libtcc.h"

#ifdef _WIN32
# define WIN32_LEAN_AND_MEAN
# include <windows.h>
#else
# include <dlfcn.h>
#endif

struct DiagnosticContext {
    JNIEnv *env;
    jobject listener;
    jmethodID on_diagnostic;
};

static char *copy_string(const char *source)
{
    size_t length = strlen(source) + 1;
    char *copy = malloc(length);
    if (copy)
        memcpy(copy, source, length);
    return copy;
}

static void report_diagnostic(void *opaque, const char *message)
{
    struct DiagnosticContext *context = opaque;
    jstring text;

    if (!context->listener)
        return;
    text = (*context->env)->NewStringUTF(context->env, message);
    if (!text)
        return;
    (*context->env)->CallVoidMethod(
        context->env, context->listener, context->on_diagnostic, text);
    (*context->env)->DeleteLocalRef(context->env, text);
}

JNIEXPORT jint JNICALL
Java_org_tinycc_TinyCC_compileNative(
    JNIEnv *env, jclass clazz, jstring runtime_directory, jstring source,
    jint output_type, jstring output_path, jstring options, jobject listener)
{
    const char *runtime_directory_chars = NULL;
    const char *source_chars = NULL;
    const char *output_path_chars = NULL;
    const char *options_chars = NULL;
    jclass listener_class = NULL;
    struct DiagnosticContext context;
    TCCState *state;
    int result;

    (void)clazz;
    if (!runtime_directory || !source || !output_path) {
        jclass exception = (*env)->FindClass(env, "java/lang/NullPointerException");
        (*env)->ThrowNew(env, exception, "runtime directory, source, and output path are required");
        return -1;
    }

    runtime_directory_chars = (*env)->GetStringUTFChars(env, runtime_directory, NULL);
    source_chars = (*env)->GetStringUTFChars(env, source, NULL);
    output_path_chars = (*env)->GetStringUTFChars(env, output_path, NULL);
    if (options)
        options_chars = (*env)->GetStringUTFChars(env, options, NULL);
    if (!runtime_directory_chars || !source_chars || !output_path_chars
        || (options && !options_chars)) {
        result = -1;
        goto done;
    }

    context.env = env;
    context.listener = listener;
    context.on_diagnostic = NULL;
    if (listener) {
        listener_class = (*env)->FindClass(env, "org/tinycc/DiagnosticListener");
        if (!listener_class) {
            result = -1;
            goto done;
        }
        context.on_diagnostic = (*env)->GetMethodID(
            env, listener_class, "onDiagnostic", "(Ljava/lang/String;)V");
        if (!context.on_diagnostic) {
            result = -1;
            goto done;
        }
    }

    state = tcc_new();
    if (!state) {
        result = -1;
        goto done;
    }
    tcc_set_lib_path(state, runtime_directory_chars);
    tcc_set_error_func(state, &context, report_diagnostic);
    result = tcc_set_output_type(state, output_type);
    if (result == 0 && options_chars && options_chars[0])
        result = tcc_set_options(state, options_chars);
    if (result == 0)
        result = tcc_compile_string(state, source_chars);
    if (result == 0)
        result = tcc_output_file(state, output_path_chars);
    tcc_delete(state);

done:
    if (options_chars)
        (*env)->ReleaseStringUTFChars(env, options, options_chars);
    if (listener_class)
        (*env)->DeleteLocalRef(env, listener_class);
    if (output_path_chars)
        (*env)->ReleaseStringUTFChars(env, output_path, output_path_chars);
    if (source_chars)
        (*env)->ReleaseStringUTFChars(env, source, source_chars);
    if (runtime_directory_chars)
        (*env)->ReleaseStringUTFChars(env, runtime_directory, runtime_directory_chars);
    return result;
}

JNIEXPORT jint JNICALL
Java_org_tinycc_TinyCC_runLibraryMainNative(
    JNIEnv *env, jclass clazz, jstring library, jobjectArray java_argv)
{
    const char *library_chars = NULL;
    char **argv = NULL;
    int argc, i, result = -1;
    typedef int (*MainFunc)(int, char **);
    MainFunc main_func = NULL;
#ifdef _WIN32
    HMODULE module = NULL;
#else
    void *module = NULL;
#endif

    (void)clazz;
    if (!library || !java_argv) {
        jclass exception = (*env)->FindClass(env, "java/lang/NullPointerException");
        (*env)->ThrowNew(env, exception, "library and argv are required");
        return -1;
    }
    library_chars = (*env)->GetStringUTFChars(env, library, NULL);
    if (!library_chars)
        goto done;

#ifdef _WIN32
    module = LoadLibraryA(library_chars);
    if (module)
        main_func = (MainFunc)(void *)GetProcAddress(module, "main");
#else
    module = dlopen(library_chars, RTLD_NOW | RTLD_LOCAL);
    if (module)
        main_func = (MainFunc)dlsym(module, "main");
#endif
    if (!main_func) {
        jclass exception = (*env)->FindClass(env, "java/lang/UnsatisfiedLinkError");
        (*env)->ThrowNew(env, exception, "shared library does not export main");
        goto done;
    }

    argc = (*env)->GetArrayLength(env, java_argv);
    argv = calloc((size_t)argc + 1, sizeof *argv);
    if (!argv)
        goto done;
    for (i = 0; i < argc; ++i) {
        jstring java_arg = (jstring)(*env)->GetObjectArrayElement(env, java_argv, i);
        const char *arg;
        if (!java_arg)
            goto done;
        arg = (*env)->GetStringUTFChars(env, java_arg, NULL);
        if (arg) {
            argv[i] = copy_string(arg);
            (*env)->ReleaseStringUTFChars(env, java_arg, arg);
        }
        (*env)->DeleteLocalRef(env, java_arg);
        if (!argv[i])
            goto done;
    }
    result = main_func(argc, argv);

done:
    if (argv) {
        for (i = 0; argv[i]; ++i)
            free(argv[i]);
        free(argv);
    }
#ifdef _WIN32
    if (module)
        FreeLibrary(module);
#else
    if (module)
        dlclose(module);
#endif
    if (library_chars)
        (*env)->ReleaseStringUTFChars(env, library, library_chars);
    return result;
}
