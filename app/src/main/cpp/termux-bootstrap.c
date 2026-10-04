#include <jni.h>

extern jbyte blob[];
extern int blob_size;

JNIEXPORT jbyteArray JNICALL Java_com_termux_app_TermuxInstaller_getZip(JNIEnv *env, __attribute__((__unused__)) jobject This)
{
    jbyteArray ret = (*env)->NewByteArray(env, blob_size);
    if (ret == NULL) {
        /* NewByteArray() already threw OutOfMemoryError; bail out so the
         * exception propagates to Java instead of crashing in SetByteArrayRegion(). */
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, ret, 0, blob_size, blob);
    return ret;
}
