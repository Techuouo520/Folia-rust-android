// UniFFI's generator. Gradle runs this on the host instead of linking it into folia-bridge.
fn main() {
    #[cfg(not(target_os = "android"))]
    uniffi::uniffi_bindgen_main();
}
