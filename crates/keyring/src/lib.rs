// The published keyring 4.2 v1 constructor returns an error on Android.
// Sessions still go through keyring::Entry, so this crate installs a real store first.
use std::sync::OnceLock;

pub use keyring_core::Error;

pub type Result<T> = keyring_core::Result<T>;

pub struct Entry {
    inner: keyring_core::Entry,
}

impl Entry {
    pub fn new(service: &str, user: &str) -> Result<Self> {
        install()?;
        Ok(Self { inner: keyring_core::Entry::new(service, user)? })
    }

    pub fn set_password(&self, password: &str) -> Result<()> {
        self.inner.set_password(password)
    }

    pub fn get_password(&self) -> Result<String> {
        self.inner.get_password()
    }

    pub fn delete_credential(&self) -> Result<()> {
        self.inner.delete_credential()
    }
}

fn install() -> Result<()> {
    static READY: OnceLock<std::result::Result<(), String>> = OnceLock::new();
    match READY.get_or_init(open_store) {
        Ok(()) => Ok(()),
        Err(message) => Err(Error::PlatformFailure(message.clone().into())),
    }
}

fn open_store() -> std::result::Result<(), String> {
    #[cfg(target_os = "android")]
    {
        let store = android_native_keyring_store::Store::new().map_err(|error| error.to_string())?;
        keyring_core::set_default_store(store);
        Ok(())
    }
    #[cfg(target_os = "windows")]
    {
        let store = windows_native_keyring_store::Store::new().map_err(|error| error.to_string())?;
        keyring_core::set_default_store(store);
        Ok(())
    }
    #[cfg(not(any(target_os = "android", target_os = "windows")))]
    Err("no credential store is wired for this platform".to_owned())
}
