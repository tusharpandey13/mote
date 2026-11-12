import Testing
@testable import MoteHelper

@Suite struct ConfigSecretsDirTests {
    @Test func secretsDirNeedsDryRun() {
        let real = Config.parse(["MoteHelper", "--secrets-dir", "/tmp/x"])
        #expect(real.secretsDirRefused)
        #expect(!real.usesSecretsDir)
        let dry = Config.parse(["MoteHelper", "--dry-run", "--secrets-dir", "/tmp/x"])
        #expect(!dry.secretsDirRefused)
        #expect(dry.usesSecretsDir)
        #expect(!Config.parse(["MoteHelper", "--dry-run"]).usesSecretsDir)
    }
}
