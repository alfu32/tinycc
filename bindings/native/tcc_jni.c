#include <jni.h>

#include "libtcc.h"

struct DiagnosticContext {
    JNIEnv *env;
    jobject listener;
    jmethodID on_diagnostic;
};

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
    jint output_type, jstring output_path, jobject listener)
{
    const char *runtime_directory_chars = NULL;
    const char *source_chars = NULL;
    const char *output_path_chars = NULL;
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
    if (!runtime_directory_chars || !source_chars || !output_path_chars) {
        result = -1;
        goto done;
    }

    context.env = env;
    context.listener = listener;
    context.on_diagnostic = NULL;
    if (listener) {
        listener_class = (*env)->GetObjectClass(env, listener);
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
    if (result == 0)
        result = tcc_compile_string(state, source_chars);
    if (result == 0)
        result = tcc_output_file(state, output_path_chars);
    tcc_delete(state);

done:
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
