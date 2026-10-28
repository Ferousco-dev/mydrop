//
//  IdentityManager.swift
//  QuickDrop
//
//  Created by Leon Böttger on 22.08.25.
//


import Foundation
import SwiftECC
import Security
import CryptoKit

class IdentityManager {
    
    static let shared = IdentityManager()
    
    private let signingKeyTag = "com.leonboettger.quickdrop.identity.signingkey"
    private var signingPrivateKey: ECPrivateKey?
    private var signingPublicKey: ECPublicKey?

    private init() {
        
        if let (privateKey, publicKey) = loadKeys() {
            
            log("[IdentityManager] Loaded existing private key.")
            
            self.signingPrivateKey = privateKey
            self.signingPublicKey = publicKey
        }
        else {
            log("[IdentityManager] No existing private key found, generating a new one.")
            let (newPrivate, newPublic) = generateAndStoreKeyPair()
            self.signingPrivateKey = newPrivate
            self.signingPublicKey = newPublic
        }
    }
    

    func getPrivateKey() -> ECPrivateKey? {
        return signingPrivateKey
    }

    
    func getPublicKey() -> ECPublicKey? {
        return signingPublicKey
    }
    
    
    private func generateAndStoreKeyPair() -> (ECPrivateKey, ECPublicKey) {
        let domain = Domain.instance(curve: .EC256r1)
        let (pubKey, privKey) = domain.makeKeyPair()

        let privateKeyData = Data(privKey.s.asSignedBytes())
        do {
            try KeychainStore.saveData(privateKeyData, account: signingKeyTag)
            AppGroup.appGroupUD.removeObject(forKey: signingKeyTag)
        } catch {
            log("[IdentityManager] Failed to store private key in keychain: \(error.localizedDescription)")
        }
        
        return (privKey, pubKey)
    }
    

    private func loadKeys() -> (ECPrivateKey, ECPublicKey)? {
        guard let keyData = loadStoredKeyData() else { return nil }
        
        let domain = Domain.instance(curve: .EC256r1)
        guard let privateKey = try? ECPrivateKey(domain: domain, s: .init(signed: Array(keyData))) else {
            log("[IdentityManager] Failed to reconstruct private key from stored data.")
            return nil
        }
        
        guard let pubPoint = try? domain.multiplyPoint(domain.g, privateKey.s) else {
            log("[IdentityManager] Failed to derive public key from private key.")
            return nil
        }
        guard let publicKey = try? ECPublicKey(domain: domain, w: pubPoint) else {
            log("[IdentityManager] Failed to reconstruct public key.")
            return nil
        }
        
        return (privateKey, publicKey)
    }


    private func loadStoredKeyData() -> Data? {
        do {
            if let keyData = try KeychainStore.loadData(account: signingKeyTag) {
                AppGroup.appGroupUD.removeObject(forKey: signingKeyTag)
                return keyData
            }
        } catch {
            log("[IdentityManager] Failed to load private key from keychain: \(error.localizedDescription)")
        }

        guard let legacyKeyData = AppGroup.appGroupUD.data(forKey: signingKeyTag) else {
            return nil
        }

        do {
            try KeychainStore.saveData(legacyKeyData, account: signingKeyTag)
            AppGroup.appGroupUD.removeObject(forKey: signingKeyTag)
            log("[IdentityManager] Migrated private key from shared defaults to keychain.")
        } catch {
            log("[IdentityManager] Failed to migrate private key to keychain: \(error.localizedDescription)")
        }

        return legacyKeyData
    }
}


extension ECPublicKey {
    
    func toGenericPublicKey() -> Securemessage_GenericPublicKey {
        var genericKey = Securemessage_GenericPublicKey()
        genericKey.type = .ecP256
        genericKey.ecP256PublicKey = Securemessage_EcP256PublicKey()
        genericKey.ecP256PublicKey.x = Data(self.w.x.asSignedBytes())
        genericKey.ecP256PublicKey.y = Data(self.w.y.asSignedBytes())
        
        return genericKey
    }
    
    
    func toGenericPublicKeyData() -> Data? {
        let genericKey = self.toGenericPublicKey()
        return try? genericKey.serializedData()
    }
}


extension Securemessage_GenericPublicKey {
    
    func id() -> Data? {
        var hasher = SHA256()
        
        guard let data = try? self.serializedData() else {
            return nil
        }
        
        hasher.update(data: data)
        let hash = hasher.finalize()
        
        return Data(hash).prefix(6)
    }
}
