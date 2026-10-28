//
//  HKDF.swift
//  QuickDrop
//
//  Created by Leon Böttger on 14.08.25.
//

import CryptoKit
import Foundation

class HKDF {
    
    static func deriveKey(ikm: SymmetricKey, salt: Data, info: Data, outputLength: Int) -> SymmetricKey {
        
        // Perform key derivation
        let hkdfKey = CryptoKit.HKDF<CryptoKit.SHA256>.deriveKey(
            inputKeyMaterial: ikm,
            salt: salt,
            info: info,
            outputByteCount: outputLength
        )
    
        return hkdfKey
    }
    
    
    static func deriveBytes(ikm: SymmetricKey, salt: Data, info: Data, outputLength: Int) -> [UInt8] {
        
        // Perform key derivation
        let hkdfKey = CryptoKit.HKDF<CryptoKit.SHA256>.deriveKey(
            inputKeyMaterial: ikm,
            salt: salt,
            info: info,
            outputByteCount: outputLength
        )
    
        return hkdfKey.withUnsafeBytes { [UInt8]($0) }
    }
}
