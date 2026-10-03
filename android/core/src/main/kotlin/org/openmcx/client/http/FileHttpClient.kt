package org.openmcx.client.http

import java.io.File

/** File bodies remain on disk; returned HTTP bodies contain only bounded control data. */
interface FileHttpClient {
    suspend fun upload(url: String, headers: Map<String, String>, info: ByteArray, file: File,
                       filename: String, progress: (Long) -> Unit): HttpResponse
    suspend fun download(url: String, headers: Map<String, String>, destination: File,
                         maxBytes: Long?, progress: (Long) -> Unit): HttpResponse
}
