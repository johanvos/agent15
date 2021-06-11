/*
 * Copyright © 2019, 2020, 2021 Peter Doornbosch
 *
 * This file is part of Agent15, an implementation of TLS 1.3 in Java.
 *
 * Agent15 is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * Agent15 is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package net.luminis.tls.handshake;

import net.luminis.tls.alert.DecodeErrorException;
import net.luminis.tls.alert.IllegalParameterAlert;
import net.luminis.tls.alert.UnsupportedExtensionAlert;
import net.luminis.tls.extension.EarlyDataExtension;
import net.luminis.tls.handshake.NewSessionTicketMessage;
import net.luminis.tls.util.ByteUtils;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NewSessionTicketMessageTest {

    @Test
    void parseValidMessage() throws Exception {
        byte[] rawData = ByteUtils.hexToBytes("0400004f 00093a80 fab00e11 04 01020304 0040 " + "00".repeat(64) + "0000");
        NewSessionTicketMessage message = new NewSessionTicketMessage().parse(ByteBuffer.wrap(rawData), rawData.length);

        assertThat(message.getTicketLifetime()).isEqualTo(604800);
        assertThat(message.getTicketNonce()).isEqualTo(new byte[] { 1, 2, 3, 4});
        assertThat(message.getTicket()).hasSize(64);
    }

    @Test
    void parseMessageWithIllegalTicketLifetime() throws Exception {
        byte[] rawData = ByteUtils.hexToBytes("0400004f 00093a81 fab00e11 04 01020304 0040 " + "00".repeat(64) + "0000");

        assertThatThrownBy(() ->
                new NewSessionTicketMessage().parse(ByteBuffer.wrap(rawData), rawData.length)
        ).isInstanceOf(IllegalParameterAlert.class);
    }

    @Test
    void parseMessageWithInappropriateExtension() throws Exception {
        //                                              lifetime age_add  nonce       ticket        extensions
        byte[] rawData = ByteUtils.hexToBytes("04000017 00093a80 fab00e11 04 01020304 0004 01020304 0004 fab0 0000");

        assertThatThrownBy(() ->
                new NewSessionTicketMessage().parse(ByteBuffer.wrap(rawData), rawData.length)
        ).isInstanceOf(UnsupportedExtensionAlert.class);
    }

    @Test
    void parseNoMessage() throws Exception {
        byte[] rawData = ByteUtils.hexToBytes("0400");
        assertThatThrownBy(() ->
                new NewSessionTicketMessage().parse(ByteBuffer.wrap(rawData), rawData.length)
        ).isInstanceOf(DecodeErrorException.class);
    }

    @Test
    void parseMessageWithInconsistentNonceLength() throws Exception {
        byte[] rawData = ByteUtils.hexToBytes("04000017 0000cafe cafebabe ff 01020304 0008 0102030405060708");
        assertThatThrownBy(() ->
                new NewSessionTicketMessage().parse(ByteBuffer.wrap(rawData), rawData.length)
        ).isInstanceOf(DecodeErrorException.class);
    }

    @Test
    void parseMessageWithInconsistentTicketLength() throws Exception {
        byte[] rawData = ByteUtils.hexToBytes("04000017 0000cafe cafebabe 04 01020304 04ff 0102030405060708");
        assertThatThrownBy(() ->
                new NewSessionTicketMessage().parse(ByteBuffer.wrap(rawData), rawData.length)
        ).isInstanceOf(DecodeErrorException.class);
    }

    @Test
    void newSessionTicketMessageMayContainGreasedExtensionType() throws Exception {
        //                                              lifetime age_add  nonce       ticket
        byte[] rawData = ByteUtils.hexToBytes("0400001f 00093a80 fab00e11 04 01020304 0004 01020304"
                // extensions length
                + "000c"
                + "baba 0000"
                + "002a 0004 01ff ffff"
        );

        EarlyDataExtension earlyDataExtension = new NewSessionTicketMessage().parse(ByteBuffer.wrap(rawData), rawData.length).getEarlyDataExtension();

        assertThat(earlyDataExtension).isNotNull();
    }
}
