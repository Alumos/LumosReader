fn main() {
    uniffi::generate_scaffolding("src/lumos_core.udl").expect("failed to generate UniFFI scaffolding");
}
