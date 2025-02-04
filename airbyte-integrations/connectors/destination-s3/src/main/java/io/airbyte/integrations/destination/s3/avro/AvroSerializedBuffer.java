/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.s3.avro;

import io.airbyte.commons.functional.CheckedBiFunction;
import io.airbyte.integrations.base.AirbyteStreamNameNamespacePair;
import io.airbyte.integrations.destination.record_buffer.BaseSerializedBuffer;
import io.airbyte.integrations.destination.record_buffer.BufferStorage;
import io.airbyte.integrations.destination.record_buffer.SerializableBuffer;
import io.airbyte.protocol.models.AirbyteRecordMessage;
import io.airbyte.protocol.models.ConfiguredAirbyteCatalog;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.apache.avro.Schema;
import org.apache.avro.file.CodecFactory;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericData.Record;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.commons.lang3.StringUtils;

public class AvroSerializedBuffer extends BaseSerializedBuffer {

  public static final String DEFAULT_SUFFIX = ".avro";

  private final CodecFactory codecFactory;
  private final Schema schema;
  private final AvroRecordFactory avroRecordFactory;
  private DataFileWriter<Record> dataFileWriter;

  public AvroSerializedBuffer(final BufferStorage bufferStorage, final CodecFactory codecFactory, final Schema schema) throws Exception {
    super(bufferStorage);
    // disable compression stream as it is already handled by codecFactory
    withCompression(false);
    this.codecFactory = codecFactory;
    this.schema = schema;
    avroRecordFactory = new AvroRecordFactory(schema, AvroConstants.JSON_CONVERTER);
    dataFileWriter = null;
  }

  @Override
  protected void createWriter(final OutputStream outputStream) throws IOException {
    dataFileWriter = new DataFileWriter<>(new GenericDatumWriter<Record>())
        .setCodec(codecFactory)
        .create(schema, outputStream);
  }

  @Override
  protected void writeRecord(final AirbyteRecordMessage recordMessage) throws IOException {
    dataFileWriter.append(avroRecordFactory.getAvroRecord(UUID.randomUUID(), recordMessage));
  }

  @Override
  protected void flushWriter() throws IOException {
    dataFileWriter.flush();
  }

  @Override
  protected void closeWriter() throws IOException {
    dataFileWriter.close();
  }

  public static CheckedBiFunction<AirbyteStreamNameNamespacePair, ConfiguredAirbyteCatalog, SerializableBuffer, Exception> createFunction(final S3AvroFormatConfig config,
                                                                                                                                          final Callable<BufferStorage> createStorageFunction) {
    final CodecFactory codecFactory = config.getCodecFactory();
    return (final AirbyteStreamNameNamespacePair stream, final ConfiguredAirbyteCatalog catalog) -> {
      final JsonToAvroSchemaConverter schemaConverter = new JsonToAvroSchemaConverter();
      final Schema schema = schemaConverter.getAvroSchema(catalog.getStreams()
          .stream()
          .filter(s -> s.getStream().getName().equals(stream.getName()) && StringUtils.equals(s.getStream().getNamespace(), stream.getNamespace()))
          .findFirst()
          .orElseThrow(() -> new RuntimeException(String.format("No such stream %s.%s", stream.getNamespace(), stream.getName())))
          .getStream()
          .getJsonSchema(),
          stream.getName(), stream.getNamespace());
      return new AvroSerializedBuffer(createStorageFunction.call(), codecFactory, schema);
    };
  }

}
