/*
 * Copyright 2012 - 2016 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.protocol;

import org.jboss.netty.channel.Channel;
import org.traccar.BaseProtocolDecoder;
import org.traccar.DeviceSession;
import org.traccar.helper.BitUtil;
import org.traccar.helper.DateBuilder;
import org.traccar.helper.Parser;
import org.traccar.helper.PatternBuilder;
import org.traccar.model.Position;

import java.net.SocketAddress;
import java.util.regex.Pattern;

public class UproProtocolDecoder extends BaseProtocolDecoder {

    public UproProtocolDecoder(UproProtocol protocol) {
        super(protocol);
    }

    private static final Pattern PATTERN = new PatternBuilder()
            .text("*")
            .expression("..20")
            .expression("([01])")                // ack
            .number("(d+),")                     // device id
            .expression("(.)")                   // type
            .expression("(.)")                   // subtype
            .expression("(.*)")                  // content
            .expression("#?")                    // delimiter
            .compile();

    private static final Pattern PATTERN_LOCATION = new PatternBuilder()
            .text("A")
            .number("(dd)(dd)(dd)")              // time
            .number("(dd)(dd)(dddd)")            // latitude
            .number("(ddd)(dd)(dddd)")           // longitude
            .number("(d)")                       // flags
            .number("(dd)")                      // speed
            .number("(dd)")                      // course
            .number("(dd)(dd)(dd)")              // date
            .compile();

    private void decodeLocation(Position position, String data) {
        Parser parser = new Parser(PATTERN_LOCATION, data);
        if (parser.matches()) {

            DateBuilder dateBuilder = new DateBuilder()
                    .setTime(parser.nextInt(), parser.nextInt(), parser.nextInt());

            position.setValid(true);
            position.setLatitude(parser.nextCoordinate(Parser.CoordinateFormat.DEG_MIN_MIN));
            position.setLongitude(parser.nextCoordinate(Parser.CoordinateFormat.DEG_MIN_MIN));

            int flags = parser.nextInt();
            position.setValid(BitUtil.check(flags, 0));
            if (!BitUtil.check(flags, 1)) {
                position.setLatitude(-position.getLatitude());
            }
            if (!BitUtil.check(flags, 2)) {
                position.setLongitude(-position.getLongitude());
            }

            position.setSpeed(parser.nextInt() * 2);
            position.setCourse(parser.nextInt() * 10);

            dateBuilder.setDateReverse(parser.nextInt(), parser.nextInt(), parser.nextInt());
            position.setTime(dateBuilder.getDate());

        }
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        Parser parser = new Parser(PATTERN, (String) msg);
        if (!parser.matches()) {
            return null;
        }

        boolean reply = parser.next().equals("1");

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, parser.next());
        if (deviceSession == null) {
            return null;
        }

        Position position = new Position();
        position.setProtocol(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        String type = parser.next();
        String subtype = parser.next();

        if (reply && channel != null) {
            channel.write("*MG20Y" + type + subtype + "#");
        }

        String[] data = parser.next().split("&");
        for (int i = 0; i < data.length; i++) {
            if (i != 0) {
                switch (data[i].charAt(0)) {
                    case 'A':
                        decodeLocation(position, data[i]);
                        break;
                    case 'B':
                        position.set(Position.KEY_STATUS, data[i].substring(1));
                        break;
                    case 'C':
                        long odometer = 0;
                        for (int j = 1; j < data[i].length(); j++) {
                            odometer <<= 4;
                            odometer += data[i].charAt(j) - '0';
                        }
                        position.set(Position.KEY_ODOMETER, odometer * 2 * 1852 / 3600);
                        break;
                    case 'P':
                        position.set(Position.KEY_MCC, Integer.parseInt(data[i].substring(1, 5)));
                        position.set(Position.KEY_MNC, Integer.parseInt(data[i].substring(5, 9)));
                        position.set(Position.KEY_LAC, Integer.parseInt(data[i].substring(9, 13), 16));
                        position.set(Position.KEY_CID, Integer.parseInt(data[i].substring(13, 17), 16));
                        break;
                    case 'S':
                        position.set("obd", data[i]);
                        break;
                    default:
                        break;
                }
            }
        }

        if (position.getLatitude() != 0 && position.getLongitude() != 0) {
            return position;
        }

        return null;
    }

}
