import Foundation
import Security

/// What this running binary is signed with (diagnostics: Keychain partition and TCC grants follow this identity).
/// Read from the running code with no UI: SecCodeCopySelf / SecCodeCopySigningInformation.
struct SigningInfo: Equatable {
    var identifier: String? = nil
    var teamId: String? = nil
    var cdhash: String? = nil
    var authorities: [String] = []
    var designatedRequirement: String? = nil
    var adhoc = false
    var flags: UInt32 = 0
    var hardenedRuntime = false
    var error: Int32? = nil          // OSStatus of the first failing call
    var bundlePath: String = ""
    var executablePath: String = ""
    var quarantined = false          // com.apple.quarantine on the bundle
    var translocated = false         // running from an App Translocation path

    /// "teamid:<TEAM>" or "cdhash:<hash>": the Keychain partition an item we create gets.
    var expectedPartition: String? {
        if let teamId { return "teamid:\(teamId)" }
        if let cdhash { return "cdhash:\(cdhash)" }
        return nil
    }

    var identityKind: String {
        if error != nil { return "unknown" }
        if adhoc { return "adhoc" }
        let a = authorities.first ?? ""
        if a.hasPrefix("Developer ID Application") { return "developer-id" }
        if a.hasPrefix("Apple Development") { return "apple-development" }
        if a.hasPrefix("Mote Helper Local") { return "mote-local" }
        return teamId == nil ? "self-signed" : "other"
    }

    var json: [String: Any] {
        var m: [String: Any] = ["identity": identityKind, "adhoc": adhoc, "hardenedRuntime": hardenedRuntime,
                                "authorities": authorities, "bundlePath": bundlePath, "executablePath": executablePath,
                                "quarantined": quarantined, "translocated": translocated]
        m["identifier"] = identifier ?? NSNull()
        m["teamId"] = teamId ?? NSNull()
        m["cdhash"] = cdhash ?? NSNull()
        m["designatedRequirement"] = designatedRequirement ?? NSNull()
        m["expectedKeychainPartition"] = expectedPartition ?? NSNull()
        if let error { m["error"] = Int(error) }
        return m
    }

    static func current() -> SigningInfo {
        var i = SigningInfo()
        i.bundlePath = Bundle.main.bundlePath
        i.executablePath = Bundle.main.executablePath ?? CommandLine.arguments.first ?? ""
        i.translocated = i.bundlePath.contains("/AppTranslocation/")
        i.quarantined = getxattr(i.bundlePath, "com.apple.quarantine", nil, 0, 0, XATTR_NOFOLLOW) >= 0
        var me: SecCode?
        var st = SecCodeCopySelf([], &me)
        guard st == errSecSuccess, let code = me else { i.error = st; return i }
        var staticCode: SecStaticCode?
        st = SecCodeCopyStaticCode(code, [], &staticCode)
        guard st == errSecSuccess, let sc = staticCode else { i.error = st; return i }
        var cf: CFDictionary?
        st = SecCodeCopySigningInformation(sc, SecCSFlags(rawValue: kSecCSSigningInformation), &cf)
        if st == errSecSuccess, let d = cf as? [String: Any] {
            i.identifier = d[kSecCodeInfoIdentifier as String] as? String
            i.teamId = d[kSecCodeInfoTeamIdentifier as String] as? String
            if let h = d[kSecCodeInfoUnique as String] as? Data { i.cdhash = h.map { String(format: "%02x", $0) }.joined() }
            i.flags = (d[kSecCodeInfoFlags as String] as? UInt32) ?? 0
            i.adhoc = i.flags & 0x2 != 0            // kSecCodeSignatureAdhoc
            i.hardenedRuntime = i.flags & 0x10000 != 0   // kSecCodeSignatureRuntime
            if let certs = d[kSecCodeInfoCertificates as String] as? [SecCertificate] {
                i.authorities = certs.compactMap { SecCertificateCopySubjectSummary($0) as String? }
            }
        } else { i.error = st }
        var req: SecRequirement?
        if SecCodeCopyDesignatedRequirement(sc, [], &req) == errSecSuccess, let req {
            var s: CFString?
            if SecRequirementCopyString(req, [], &s) == errSecSuccess { i.designatedRequirement = s as String? }
        }
        return i
    }
}
