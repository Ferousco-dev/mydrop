//
//  AppGroup.swift
//  QuickDrop
//
//  Created by Leon Böttger on 23.08.25.
//

import Foundation
import Security

/// Contains information about the app group used to share data between main app and extensions
struct AppGroup {
    
    /// Identifier of the app group
    static let appGroupName = "group.com.leonboettger.neardrop"
    static let appGroupUD = UserDefaults(suiteName: AppGroup.appGroupName)!
    
    /// Returns the shared App Group directory
    static var appGroupDirectory: URL? {
        return FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupName)
    }
}


struct KeychainStore {
    private static let service = "com.leonboettger.quickdrop.securestorage"
    // Must match the shared keychain access group declared in all Apple entitlements.
    private static let sharedAccessGroup = "92SDAWLN76.com.leonboettger.neardrop"

    static func loadData(account: String) throws -> Data? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        switch status {
        case errSecSuccess:
            return result as? Data
        case errSecItemNotFound:
            return nil
        default:
            throw KeychainStoreError.unexpectedStatus(status)
        }
    }


    static func saveData(_ data: Data, account: String) throws {
        var addQuery = baseQuery(account: account)
        addQuery[kSecValueData as String] = data
        addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly

        let addStatus = SecItemAdd(addQuery as CFDictionary, nil)
        if addStatus == errSecSuccess {
            return
        }
        if addStatus != errSecDuplicateItem {
            throw KeychainStoreError.unexpectedStatus(addStatus)
        }

        let attributesToUpdate: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]

        let updateStatus = SecItemUpdate(baseQuery(account: account) as CFDictionary, attributesToUpdate as CFDictionary)
        guard updateStatus == errSecSuccess else {
            throw KeychainStoreError.unexpectedStatus(updateStatus)
        }
    }


    static func deleteItem(account: String) throws {
        let status = SecItemDelete(baseQuery(account: account) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainStoreError.unexpectedStatus(status)
        }
    }


    private static func baseQuery(account: String) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecUseDataProtectionKeychain as String: true,
        ]

        query[kSecAttrAccessGroup as String] = sharedAccessGroup

        return query
    }
}


enum KeychainStoreError: LocalizedError {
    case unexpectedStatus(OSStatus)

    var errorDescription: String? {
        switch self {
        case let .unexpectedStatus(status):
            if let message = SecCopyErrorMessageString(status, nil) as String? {
                return "Keychain error \(status): \(message)"
            }
            return "Keychain error \(status)"
        }
    }
}
