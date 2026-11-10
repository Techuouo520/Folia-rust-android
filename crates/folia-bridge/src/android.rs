// One-time Android context for cpal AAudio. The global reference stays alive for the process.
use std::ffi::c_void;
use std::sync::atomic::{AtomicBool, AtomicPtr, Ordering};

use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JClass, JObject};

static STARTED: AtomicBool = AtomicBool::new(false);
static CONTEXT: AtomicPtr<c_void> = AtomicPtr::new(std::ptr::null_mut());

#[derive(Debug, thiserror::Error)]
enum InitError {
    #[error("android context is null")]
    NullContext,
    #[error("{0}")]
    Jni(String),
}

impl From<jni::errors::Error> for InitError {
    fn from(error: jni::errors::Error) -> Self {
        InitError::Jni(error.to_string())
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_techuouo520_folia_AndroidContext_nativeInit<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    context: JObject<'local>,
) {
    env.with_env(|env| install(env, context)).resolve::<ThrowRuntimeExAndDefault>();
}

fn install(env: &mut jni::Env<'_>, context: JObject<'_>) -> Result<(), InitError> {
    crate::init_tracing();
    if context.is_null() {
        tracing::error!("android context is null");
        return Err(InitError::NullContext);
    }
    if STARTED.swap(true, Ordering::AcqRel) {
        tracing::warn!("android context is already initialized");
        return Ok(());
    }
    match keep(env, context) {
        Ok(()) => Ok(()),
        Err(error) => {
            STARTED.store(false, Ordering::Release);
            tracing::error!(error = &error as &dyn std::error::Error, "android context initialization failed");
            Err(error)
        }
    }
}

fn keep(env: &mut jni::Env<'_>, context: JObject<'_>) -> Result<(), InitError> {
    let vm = env.get_java_vm()?;
    let verifier_context = env.new_local_ref(&context)?;
    rustls_platform_verifier::android::init_with_env(env, verifier_context).map_err(InitError::from)?;
    let global = env.new_global_ref(&context)?;
    if global.as_obj().is_null() {
        return Err(InitError::NullContext);
    }
    let vm_ptr = vm.get_raw();
    let context_ptr = global.into_raw();
    CONTEXT.store(context_ptr.cast(), Ordering::Release);
    // SAFETY: context_ptr is a NewGlobalRef of the application Context retained in CONTEXT for the
    // process lifetime. vm_ptr is the JavaVM that invoked this native method. ndk-context stores both
    // pointers and never releases them.
    unsafe {
        ndk_context::initialize_android_context(vm_ptr.cast(), context_ptr.cast());
    }
    tracing::info!("android context initialized");
    Ok(())
}
