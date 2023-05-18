/*
 * Copyright 2023-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.stream.binder.kinesis;

import java.util.Map;

import org.springframework.cloud.stream.binder.EmbeddedHeaderUtils;
import org.springframework.cloud.stream.binder.MessageValues;
import org.springframework.integration.mapping.BytesMessageMapper;
import org.springframework.integration.support.json.EmbeddedJsonHeadersMessageMapper;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;

/**
 * The {@link BytesMessageMapper} implementation to support an embedded headers
 * format from {@link EmbeddedHeaderUtils} to be able to receive and deserialize
 * properly messages published from the previous binder version.
 * The current Kinesis binder deals with embedded headers format from {@link EmbeddedJsonHeadersMessageMapper}.
 * <p>
 * The {@link #toMessage(byte[], Map)} first checks for the {@link EmbeddedHeaderUtils#mayHaveEmbeddedHeaders(byte[])}
 * and tries to deserialize embedded headers via {@link EmbeddedHeaderUtils#extractHeaders(byte[])},
 * then it falls back to the {@link EmbeddedJsonHeadersMessageMapper} for a new format.
 *
 * @author Artem Bilan
 *
 * @since 4.0
 */
class LegacyEmbeddedHeadersSupportBytesMessageMapper implements BytesMessageMapper {

	private final EmbeddedJsonHeadersMessageMapper delegate;

	private final String[] headersToEmbed;

	private final boolean legacyEmbeddedHeadersFormat;

	LegacyEmbeddedHeadersSupportBytesMessageMapper(boolean legacyEmbeddedHeadersFormat, String[] headersToEmbed) {
		this.legacyEmbeddedHeadersFormat = legacyEmbeddedHeadersFormat;
		this.delegate = new EmbeddedJsonHeadersMessageMapper(headersToEmbed);
		this.headersToEmbed = headersToEmbed;
	}

	@Override
	public Message<?> toMessage(byte[] payload, @Nullable Map<String, Object> headers) {
		if (EmbeddedHeaderUtils.mayHaveEmbeddedHeaders(payload)) {
			MessageValues messageValues;
			try {
				messageValues = EmbeddedHeaderUtils.extractHeaders(payload);
				if (headers != null) {
					messageValues.copyHeadersIfAbsent(headers);
				}
				return messageValues.toMessage();
			}
			catch (Exception ex) {
				/*
				 * Ignore an exception since we don't know for sure that it
				 * really is a message with embedded headers, it just meets the
				 * criteria in EmbeddedHeaderUtils.mayHaveEmbeddedHeaders().
				 * Fall back to the EmbeddedJsonHeadersMessageMapper delegate for a new format attempt.
				 */
			}
		}

		return this.delegate.toMessage(payload, headers);
	}

	@Override
	public byte[] fromMessage(Message<?> message) {
		if (this.legacyEmbeddedHeadersFormat) {
			MessageValues transformed = new MessageValues(message);
			Object contentType = transformed.get(MessageHeaders.CONTENT_TYPE);
			// transform content type headers to String, so that they can be properly embedded in JSON
			if (contentType != null) {
				transformed.put(MessageHeaders.CONTENT_TYPE, contentType.toString());
			}
			return EmbeddedHeaderUtils.embedHeaders(transformed, this.headersToEmbed);
		}
		else {
			return this.delegate.fromMessage(message);
		}
	}

}
