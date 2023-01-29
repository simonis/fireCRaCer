//
// g++ -fPIC -shared -I $JAVA_HOME/include/ -I $JAVA_HOME/include/linux/ -o libSuspendResumeAgent.so SuspendResumeAgent.cpp
//
#include <jvmti.h>
#include <stdio.h>
#include <string.h>

jvmtiEnv *jvmti = NULL;

static int setupJVMTI(JNIEnv *env, JavaVM *jvm) {
  if (jvmti == NULL) {
    if (jvm == NULL) {
      jint result = env->GetJavaVM(&jvm);
      if (result != JNI_OK) {
        fprintf(stderr, "Can't get JavaVM!\n");
        return JNI_ERR;
      }
    }
    jint result = jvm->GetEnv((void**) &jvmti, JVMTI_VERSION_1_1);
    if (result != JNI_OK) {
      fprintf(stderr, "Can't access JVMTI!\n");
      return JNI_ERR;
    }
  }
  jvmtiCapabilities capabilities;
  jvmtiError error;
  /*
  memset(&capabilities, 0, sizeof(capabilities));
  error = jvmti->GetPotentialCapabilities(&capabilities);
  if (error != JVMTI_ERROR_NONE) {
    printf("Can't get potential capabilities: %d\n", error);
  }
  fprintf(stdout, "Potential capability can_suspend = %d\n", capabilities.can_suspend);
  */
  memset(&capabilities, 0, sizeof(capabilities));
  capabilities.can_suspend = 1;
  error = jvmti->AddCapabilities(&capabilities);
  if (error != JVMTI_ERROR_NONE) {
    printf("Can't add 'can_suspend' capability: %d\n", error);
  }

}

extern "C"
JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
  fprintf(stdout, "Agent_OnLoad\n");
  setupJVMTI(NULL, vm);
}

extern "C"
JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char *options, void *reserved) {
  fprintf(stdout, "Agent_OnAttach\n");
  setupJVMTI(NULL, vm);
}

extern "C"
JNIEXPORT jint JNICALL Java_io_simonis_SuspendResumeAgent_forceGC(JNIEnv *env, jclass cls) {
  setupJVMTI(env, NULL);
  jvmtiError error = jvmti->ForceGarbageCollection();
  if (error != JVMTI_ERROR_NONE) {
    printf("Error during ForceGarbageCollection: %d\n", error);
  } else {
    printf("Successfully called ForceGarbageCollection\n");
  }
  return error;
}

// Suspend all threads (excluding ourselves) and return the number of suspended threads.
// If not all threads could be suspended, return the number of threads for which
// suspension failed as a negative number.
extern "C"
JNIEXPORT jint JNICALL Java_io_simonis_SuspendResumeAgent_suspendThreads(JNIEnv *env, jclass cls) {
  setupJVMTI(env, NULL);
  jthread current;
  jvmti->GetCurrentThread(&current);
  jint threads_count;
  jthread *threads;
  jvmti->GetAllThreads(&threads_count, &threads);
  for (int t = 0; t < threads_count; t++) {
    jvmtiThreadInfo ti;
    jvmti->GetThreadInfo(threads[t], &ti);
    // printf("%d: %s %s\n", t, ti.name, env->IsSameObject(current, threads[t]) ? "(current)" : "");
    // We don't want to suspend ourselves
    if (env->IsSameObject(current, threads[t])) {
      threads[t] = threads[threads_count - 1];
    }
  }
  jvmtiError *errors = new jvmtiError[threads_count];
  jvmtiError error = jvmti->SuspendThreadList(threads_count - 1, threads, errors);
  if (error != JVMTI_ERROR_NONE) {
    printf("Error during SuspendThreadList: %d\n", error);
  }
  int failCount = 0;
  for (int t = 0; t < threads_count - 1; t++) {
    jvmtiThreadInfo ti;
    jvmti->GetThreadInfo(threads[t], &ti);
    if (errors[t] != JVMTI_ERROR_NONE) {
      printf("Failed to suspend thread %d: %s (%d)\n", t, ti.name, errors[t]);
      failCount++;
    } else {
      // printf("Successfully suspended thread %d: %s\n", t, ti.name);
    }
  }
  if (failCount > 0) {
    printf("Error during SuspendThreadList: can't suspend %d out of %d threads\n", failCount, threads_count - 1);
    return - failCount;
  } else {
    printf("SuspendThreadList successfully suspended %d threads\n", threads_count - 1);
    return threads_count - 1;
  }
}

// Resume all threads and return the number of resumed thread excluding
// ourselves since we haven't been suspended by suspendThreads().
// If not all threads could be resumed, return the number of threads for which resuming
// failed as a negative number.
extern "C"
JNIEXPORT jint JNICALL Java_io_simonis_SuspendResumeAgent_resumeThreads(JNIEnv *env, jclass cls) {
  setupJVMTI(env, NULL);
  jthread current;
  jvmti->GetCurrentThread(&current);
  jint threads_count;
  jthread *threads;
  jvmti->GetAllThreads(&threads_count, &threads);
  jvmtiError *errors = new jvmtiError[threads_count];
  jvmtiError error = jvmti->ResumeThreadList(threads_count, threads, errors);
  if (error != JVMTI_ERROR_NONE) {
    printf("Error during ResumeThreadList: %d\n", error);
  }
  int failCount = 0;
  for (int t = 0; t < threads_count; t++) {
    jvmtiThreadInfo ti;
    jvmti->GetThreadInfo(threads[t], &ti);
    if (errors[t] != JVMTI_ERROR_NONE &&
        // Our current thread is not suspended
        !(errors[t] == JVMTI_ERROR_THREAD_NOT_SUSPENDED && env->IsSameObject(current, threads[t]))) {
      printf("Failed to resume thread %d: %s (%d)\n", t, ti.name, errors[t]);
      failCount++;
    } else {
      // printf("Successfully resumed thread %d: %s\n", t, ti.name);
    }
  }
  if (failCount > 0) {
    printf("Error during ResumeThreadList: can't resume %d out of %d threads\n", failCount, threads_count - 1);
    return - failCount;
  } else {
    printf("ResumeThreadList successfully resumed %d threads\n", threads_count - 1);
    return threads_count - 1;
  }
}
