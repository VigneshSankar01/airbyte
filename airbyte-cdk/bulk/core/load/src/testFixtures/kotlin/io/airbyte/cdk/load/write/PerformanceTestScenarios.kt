/*
 * Copyright (c) 2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.cdk.load.write

import io.airbyte.cdk.load.command.Append
import io.airbyte.cdk.load.command.Dedupe
import io.airbyte.cdk.load.command.DestinationCatalog
import io.airbyte.cdk.load.command.DestinationStream
import io.airbyte.cdk.load.data.FieldType
import io.airbyte.cdk.load.data.ObjectType
import io.airbyte.cdk.load.data.ObjectTypeWithoutSchema
import io.airbyte.cdk.load.message.DestinationFile
import io.airbyte.cdk.load.message.InputRecord
import io.airbyte.cdk.load.test.util.destination_process.DestinationProcess
import io.airbyte.protocol.models.Jsons
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.random.Random

/**
 * Single stream performance test.
 *
 * This performance scenario will insert [recordsToInsert] records are generated from the [idColumn]
 * and [columns] parameters. Records are the same except for the id which will be automatically
 * incremented. [dedup] controls whether the insert mode is `Append` or `Dedupe`. [duplicateChance]
 * if non 0 will insert random duplicates of records.
 */
class SingleStreamInsert(
    private val idColumn: NamedField,
    private val columns: List<NamedField>,
    private val recordsToInsert: Long,
    private val dedup: Boolean = false,
    duplicateChance: Double = 0.0,
    randomizedNamespace: String,
    streamName: String,
    generationId: Long = 0,
    minGenerationId: Long = 0,
) : PerformanceTestScenario {

    init {
        assert(duplicateChance in 0.0..1.0)
    }

    private val stream = run {
        val importType =
            if (!dedup) Append
            else
                Dedupe(
                    primaryKey = listOf(listOf(idColumn.name)),
                    cursor = listOf(idColumn.name),
                )
        val schema =
            (listOf(idColumn) + columns).map {
                Pair(it.name, FieldType(type = it.type, nullable = true))
            }

        DestinationStream(
            descriptor = DestinationStream.Descriptor(randomizedNamespace, streamName),
            importType = importType,
            schema = ObjectType(linkedMapOf(*schema.toTypedArray())),
            generationId = generationId,
            minimumGenerationId = minGenerationId,
            syncId = 1,
        )
    }

    private val random = SecureRandom()
    private val randomThreshold: Int =
        if (duplicateChance > 0.0) ((duplicateChance % 1.0) * 100).toInt() else 0

    private var recordCount: Long = 0
    private var byteCount: Long = 0

    override val catalog = DestinationCatalog(listOf(stream))

    class RecordWriter(
        indexColumn: NamedField,
        columns: List<NamedField>,
        stream: DestinationStream,
        private val destination: DestinationProcess,
        private val recordBufferSize: Long = 1,
    ) : AutoCloseable {
        private val baseRecord = run {
            val data = (listOf(indexColumn) + columns).associate { Pair(it.name, it.sample) }
            InputRecord(
                namespace = stream.descriptor.namespace,
                name = stream.descriptor.name,
                data = Jsons.serialize(data),
                emittedAtMs = System.currentTimeMillis(),
            )
        }
        private val messageParts =
            Jsons.serialize(baseRecord.asProtocolMessage()).split(indexColumn.sample.toString())
        private val baseMessageSize = messageParts.sumOf { it.length }

        private val sb = StringBuilder()

        var recordWritten: Long = 0
        var bytesWritten: Long = 0

        fun write(id: Long) {
            sb.append(messageParts[0])
            sb.append(id)
            sb.append(messageParts[1])
            sb.appendLine()

            if (recordWritten % recordBufferSize == 0L) {
                flush()
            }

            recordWritten += 1
            bytesWritten += baseMessageSize + id.length()
        }

        private fun flush() {
            if (sb.isNotEmpty()) {
                destination.sendMessage(sb.toString())
                sb.clear()
            }
        }

        override fun close() {
            flush()
        }
    }

    override fun send(destination: DestinationProcess) {
        RecordWriter(
                indexColumn = idColumn,
                columns = columns,
                stream = stream,
                destination = destination,
                recordBufferSize = 10,
            )
            .use { writer ->
                (1..recordsToInsert).forEach {
                    writer.write(it)
                    if (randomThreshold > 0 && random.nextInt(0, 100) <= randomThreshold) {
                        writer.write(it)
                    }
                }
                recordCount = writer.recordWritten
                byteCount = writer.bytesWritten
            }
    }

    override fun getSummary() =
        PerformanceTestScenario.Summary(
            recordCount,
            byteCount,
            expectedRecordsCount = if (dedup) recordsToInsert else recordCount,
        )
}

class SingleStreamFileTransfer(
    private val randomizedNamespace: String,
    private val streamName: String,
    private val numFiles: Int,
    private val fileSizeMb: Int,
    private val stagingDirectory: Path,
    private val seed: Long = 8656931613L
) : PerformanceTestScenario {
    private val log = KotlinLogging.logger {}

    private val descriptor = DestinationStream.Descriptor(randomizedNamespace, streamName)
    private val stream =
        DestinationStream(
            descriptor = DestinationStream.Descriptor(randomizedNamespace, streamName),
            importType = Append,
            schema = ObjectType(linkedMapOf()),
            generationId = 1,
            minimumGenerationId = 0,
            syncId = 1,
        )

    override val catalog: DestinationCatalog =
        DestinationCatalog(
            listOf(
                DestinationStream(
                    descriptor = descriptor,
                    importType = Append,
                    schema = ObjectTypeWithoutSchema,
                    generationId = 1,
                    minimumGenerationId = 1,
                    syncId = 101
                )
            )
        )

    private fun makeFileName(index: Long): String =
        "test_file__${randomizedNamespace}__${streamName}__$index.txt"

    fun setup() {
        // TODO: Maybe make these files different sizes?
        val prng = Random(seed)
        val randomMegabyte = ByteArray(1024 * 1024) { prng.nextInt().toByte() }
        repeat(numFiles) {
            val file = stagingDirectory.resolve(makeFileName(it.toLong()))
            log.info { "Creating file $file with size ${fileSizeMb}mb" }
            val outputStream = file.toFile().outputStream()
            repeat(fileSizeMb) { outputStream.write(randomMegabyte) }
            outputStream.close()
        }
    }

    override fun send(destination: DestinationProcess) {
        repeat(numFiles) {
            val fileName = makeFileName(it.toLong())
            val message =
                DestinationFile(
                    stream,
                    System.currentTimeMillis(),
                    "",
                    DestinationFile.AirbyteRecordMessageFile(
                        fileUrl = stagingDirectory.resolve(fileName).toString(),
                        fileRelativePath = fileName,
                        bytes = fileSizeMb * 1024 * 1024L,
                        modified = System.currentTimeMillis(),
                        sourceFileUrl = fileName,
                    )
                )
            destination.sendMessage(message.asProtocolMessage())
        }
    }

    override fun getSummary(): PerformanceTestScenario.Summary =
        PerformanceTestScenario.Summary(
            records = numFiles.toLong(),
            size = numFiles * fileSizeMb * 1024 * 1024L,
            expectedRecordsCount = numFiles.toLong()
        )
}

private fun Long.length(): Long =
    if (this <= 99999) {
        if (this <= 99) {
            if (this <= 9) {
                1
            } else {
                2
            }
        } else {
            if (this <= 999) {
                3
            } else {
                if (this <= 9999) {
                    4
                } else {
                    5
                }
            }
        }
    } else {
        if (this <= 9999999) {
            if (this <= 999999) {
                6
            } else {
                7
            }
        } else {
            if (this <= 99999999) {
                8
            } else {
                if (this <= 999999999) {
                    9
                } else {
                    10
                }
            }
        }
    }
