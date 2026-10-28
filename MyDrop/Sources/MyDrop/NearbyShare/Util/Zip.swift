//
//  Zip.swift
//  QuickDrop
//
//  Created by Leon Böttger on 11.03.25.
//

import Foundation
import ZIPFoundation

final class Zip {

    static func createAtTemporaryDirectory(
        zipFilename: String,
        zipExtension: String = "zip",
        fromDirectory directoryURL: URL
    ) throws -> URL {

        guard directoryURL.isDirectory else { throw CreateZipError.urlNotADirectory(directoryURL) }

        let zipFinalURL = FileManager.default.temporaryDirectory
            .appendingPathComponent(zipFilename)
            .appendingPathExtension(zipExtension)

        if FileManager.default.fileExists(atPath: zipFinalURL.path) {
            try FileManager.default.removeItem(at: zipFinalURL)
        }

        try FileManager.default.zipItem(
            at: directoryURL,
            to: zipFinalURL,
            shouldKeepParent: true,
            compressionMethod: .deflate
        )

        return zipFinalURL
    }
    
    enum CreateZipError: Error {
        case urlNotADirectory(URL)
    }
}

private extension URL {
    var isDirectory: Bool {
        (try? resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true
    }
}
