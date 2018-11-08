package org.apache.cassandra.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.concurrent.FastThreadLocal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import org.apache.cassandra.db.ConsistencyLevel;
import org.apache.cassandra.db.TypeSizes;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.Pair;
import org.apache.cassandra.utils.UUIDGen;

public abstract class CBUtil {
   public static final boolean USE_HEAP_ALLOCATOR = Boolean.getBoolean("cassandra.netty_use_heap_allocator");
   public static final ByteBufAllocator allocator;
   private static final int UUID_SIZE = 16;
   private static final FastThreadLocal<CharsetDecoder> TL_UTF8_DECODER;
   private static final FastThreadLocal<CharBuffer> TL_CHAR_BUFFER;

   private CBUtil() {
   }

   private static String decodeString(ByteBuffer src) throws CharacterCodingException {
      CharsetDecoder theDecoder = (CharsetDecoder)TL_UTF8_DECODER.get();
      theDecoder.reset();
      CharBuffer dst = (CharBuffer)TL_CHAR_BUFFER.get();
      int capacity = (int)((double)src.remaining() * (double)theDecoder.maxCharsPerByte());
      if(dst == null) {
         capacity = Math.max(capacity, 4096);
         dst = CharBuffer.allocate(capacity);
         TL_CHAR_BUFFER.set(dst);
      } else {
         dst.clear();
         if(dst.capacity() < capacity) {
            dst = CharBuffer.allocate(capacity);
            TL_CHAR_BUFFER.set(dst);
         }
      }

      CoderResult cr = theDecoder.decode(src, dst, true);
      if(!cr.isUnderflow()) {
         cr.throwException();
      }

      return dst.flip().toString();
   }

   private static String readString(ByteBuf cb, int length) {
      if(length == 0) {
         return "";
      } else {
         ByteBuffer buffer = cb.nioBuffer(cb.readerIndex(), length);

         try {
            String str = decodeString(buffer);
            cb.readerIndex(cb.readerIndex() + length);
            return str;
         } catch (CharacterCodingException | IllegalStateException var4) {
            throw new ProtocolException("Cannot decode string as UTF8: '" + ByteBufferUtil.bytesToHex(buffer) + "'; " + var4);
         }
      }
   }

   public static String readString(ByteBuf cb) {
      try {
         int length = cb.readUnsignedShort();
         return readString(cb, length);
      } catch (IndexOutOfBoundsException var2) {
         throw new ProtocolException("Not enough bytes to read an UTF8 serialized string preceded by its 2 bytes length");
      }
   }

   public static void writeString(String str, ByteBuf cb) {
      int writerIndex = cb.writerIndex();
      cb.writeShort(0);
      int written = ByteBufUtil.writeUtf8(cb, str);
      cb.setShort(writerIndex, written);
   }

   public static int sizeOfString(String str) {
      return 2 + TypeSizes.encodedUTF8Length(str);
   }

   public static String readLongString(ByteBuf cb) {
      try {
         int length = cb.readInt();
         return readString(cb, length);
      } catch (IndexOutOfBoundsException var2) {
         throw new ProtocolException("Not enough bytes to read an UTF8 serialized string preceded by its 4 bytes length");
      }
   }

   public static void writeLongString(String str, ByteBuf cb) {
      int writerIndex = cb.writerIndex();
      cb.writeInt(0);
      int written = ByteBufUtil.writeUtf8(cb, str);
      cb.setInt(writerIndex, written);
   }

   public static int sizeOfLongString(String str) {
      return 4 + TypeSizes.encodedUTF8Length(str);
   }

   public static byte[] readBytes(ByteBuf cb) {
      try {
         int length = cb.readUnsignedShort();
         byte[] bytes = new byte[length];
         cb.readBytes(bytes);
         return bytes;
      } catch (IndexOutOfBoundsException var3) {
         throw new ProtocolException("Not enough bytes to read a byte array preceded by its 2 bytes length");
      }
   }

   public static void writeBytes(byte[] bytes, ByteBuf cb) {
      cb.writeShort(bytes.length);
      cb.writeBytes(bytes);
   }

   public static int sizeOfBytes(byte[] bytes) {
      return 2 + bytes.length;
   }

   public static Map<String, ByteBuffer> readBytesMap(ByteBuf cb) {
      int length = cb.readUnsignedShort();
      Map<String, ByteBuffer> m = new HashMap(length);

      for(int i = 0; i < length; ++i) {
         String k = readString(cb);
         ByteBuffer v = readValue(cb);
         m.put(k, v);
      }

      return m;
   }

   public static void writeBytesMap(Map<String, ByteBuffer> m, ByteBuf cb) {
      cb.writeShort(m.size());
      Iterator var2 = m.entrySet().iterator();

      while(var2.hasNext()) {
         Entry<String, ByteBuffer> entry = (Entry)var2.next();
         writeString((String)entry.getKey(), cb);
         writeValue((ByteBuffer)entry.getValue(), cb);
      }

   }

   public static int sizeOfBytesMap(Map<String, ByteBuffer> m) {
      int size = 2;

      Entry entry;
      for(Iterator var2 = m.entrySet().iterator(); var2.hasNext(); size += sizeOfValue((ByteBuffer)entry.getValue())) {
         entry = (Entry)var2.next();
         size += sizeOfString((String)entry.getKey());
      }

      return size;
   }

   public static ConsistencyLevel readConsistencyLevel(ByteBuf cb) {
      return ConsistencyLevel.fromCode(cb.readUnsignedShort());
   }

   public static void writeConsistencyLevel(ConsistencyLevel consistency, ByteBuf cb) {
      cb.writeShort(consistency.code);
   }

   public static int sizeOfConsistencyLevel(ConsistencyLevel consistency) {
      return 2;
   }

   public static <T extends Enum<T>> T readEnumValue(Class<T> enumType, ByteBuf cb) {
      String value = readString(cb);

      try {
         return Enum.valueOf(enumType, value.toUpperCase());
      } catch (IllegalArgumentException var4) {
         throw new ProtocolException(String.format("Invalid value '%s' for %s", new Object[]{value, enumType.getSimpleName()}));
      }
   }

   public static <T extends Enum<T>> void writeEnumValue(T enumValue, ByteBuf cb) {
      writeString(enumValue.toString(), cb);
   }

   public static <T extends Enum<T>> int sizeOfEnumValue(T enumValue) {
      return sizeOfString(enumValue.toString());
   }

   public static UUID readUUID(ByteBuf cb) {
      ByteBuffer buffer = cb.nioBuffer(cb.readerIndex(), 16);
      cb.skipBytes(buffer.remaining());
      return UUIDGen.getUUID(buffer);
   }

   public static void writeUUID(UUID uuid, ByteBuf cb) {
      cb.writeBytes(UUIDGen.decompose(uuid));
   }

   public static int sizeOfUUID(UUID uuid) {
      return 16;
   }

   public static List<String> readStringList(ByteBuf cb) {
      int length = cb.readUnsignedShort();
      List<String> l = new ArrayList(length);

      for(int i = 0; i < length; ++i) {
         l.add(readString(cb));
      }

      return l;
   }

   public static void writeStringList(List<String> l, ByteBuf cb) {
      cb.writeShort(l.size());
      Iterator var2 = l.iterator();

      while(var2.hasNext()) {
         String str = (String)var2.next();
         writeString(str, cb);
      }

   }

   public static int sizeOfStringList(List<String> l) {
      int size = 2;

      String str;
      for(Iterator var2 = l.iterator(); var2.hasNext(); size += sizeOfString(str)) {
         str = (String)var2.next();
      }

      return size;
   }

   public static Map<String, String> readStringMap(ByteBuf cb) {
      int length = cb.readUnsignedShort();
      Map<String, String> m = new HashMap(length);

      for(int i = 0; i < length; ++i) {
         String k = readString(cb);
         String v = readString(cb);
         m.put(k, v);
      }

      return m;
   }

   public static void writeStringMap(Map<String, String> m, ByteBuf cb) {
      cb.writeShort(m.size());
      Iterator var2 = m.entrySet().iterator();

      while(var2.hasNext()) {
         Entry<String, String> entry = (Entry)var2.next();
         writeString((String)entry.getKey(), cb);
         writeString((String)entry.getValue(), cb);
      }

   }

   public static int sizeOfStringMap(Map<String, String> m) {
      int size = 2;

      Entry entry;
      for(Iterator var2 = m.entrySet().iterator(); var2.hasNext(); size += sizeOfString((String)entry.getValue())) {
         entry = (Entry)var2.next();
         size += sizeOfString((String)entry.getKey());
      }

      return size;
   }

   public static Map<String, List<String>> readStringToStringListMap(ByteBuf cb) {
      int length = cb.readUnsignedShort();
      Map<String, List<String>> m = new HashMap(length);

      for(int i = 0; i < length; ++i) {
         String k = readString(cb).toUpperCase();
         List<String> v = readStringList(cb);
         m.put(k, v);
      }

      return m;
   }

   public static void writeStringToStringListMap(Map<String, List<String>> m, ByteBuf cb) {
      cb.writeShort(m.size());
      Iterator var2 = m.entrySet().iterator();

      while(var2.hasNext()) {
         Entry<String, List<String>> entry = (Entry)var2.next();
         writeString((String)entry.getKey(), cb);
         writeStringList((List)entry.getValue(), cb);
      }

   }

   public static int sizeOfStringToStringListMap(Map<String, List<String>> m) {
      int size = 2;

      Entry entry;
      for(Iterator var2 = m.entrySet().iterator(); var2.hasNext(); size += sizeOfStringList((List)entry.getValue())) {
         entry = (Entry)var2.next();
         size += sizeOfString((String)entry.getKey());
      }

      return size;
   }

   public static ByteBuffer readValue(ByteBuf cb) {
      int length = cb.readInt();
      return length < 0?null:ByteBuffer.wrap(readRawBytes(cb, length));
   }

   public static ByteBuffer readValueNoCopy(ByteBuf cb) {
      int length = cb.readInt();
      if(length < 0) {
         return null;
      } else {
         ByteBuffer buffer = cb.nioBuffer(cb.readerIndex(), length);
         cb.skipBytes(length);
         return buffer;
      }
   }

   public static ByteBuffer readBoundValue(ByteBuf cb, ProtocolVersion protocolVersion) {
      int length = cb.readInt();
      if(length < 0) {
         if(protocolVersion.isSmallerThan(ProtocolVersion.V4)) {
            return null;
         } else if(length == -1) {
            return null;
         } else if(length == -2) {
            return ByteBufferUtil.UNSET_BYTE_BUFFER;
         } else {
            throw new ProtocolException("Invalid ByteBuf length " + length);
         }
      } else {
         return ByteBuffer.wrap(readRawBytes(cb, length));
      }
   }

   public static void writeValue(byte[] bytes, ByteBuf cb) {
      if(bytes == null) {
         cb.writeInt(-1);
      } else {
         cb.writeInt(bytes.length);
         cb.writeBytes(bytes);
      }
   }

   public static void writeValue(ByteBuffer bytes, ByteBuf cb) {
      if(bytes == null) {
         cb.writeInt(-1);
      } else {
         int remaining = bytes.remaining();
         cb.writeInt(remaining);
         if(remaining > 0) {
            cb.writeBytes(bytes.duplicate());
         }

      }
   }

   public static int sizeOfValue(byte[] bytes) {
      return 4 + (bytes == null?0:bytes.length);
   }

   public static int sizeOfValue(ByteBuffer bytes) {
      return 4 + (bytes == null?0:bytes.remaining());
   }

   public static int sizeOfValue(int valueSize) {
      return 4 + (valueSize < 0?0:valueSize);
   }

   public static List<ByteBuffer> readValueList(ByteBuf cb, ProtocolVersion protocolVersion) {
      int size = cb.readUnsignedShort();
      if(size == 0) {
         return Collections.emptyList();
      } else {
         List<ByteBuffer> l = new ArrayList(size);

         for(int i = 0; i < size; ++i) {
            l.add(readBoundValue(cb, protocolVersion));
         }

         return l;
      }
   }

   public static void writeValueList(List<ByteBuffer> values, ByteBuf cb) {
      cb.writeShort(values.size());
      Iterator var2 = values.iterator();

      while(var2.hasNext()) {
         ByteBuffer value = (ByteBuffer)var2.next();
         writeValue(value, cb);
      }

   }

   public static int sizeOfValueList(List<ByteBuffer> values) {
      int size = 2;

      ByteBuffer value;
      for(Iterator var2 = values.iterator(); var2.hasNext(); size += sizeOfValue(value)) {
         value = (ByteBuffer)var2.next();
      }

      return size;
   }

   public static Pair<List<String>, List<ByteBuffer>> readNameAndValueList(ByteBuf cb, ProtocolVersion protocolVersion) {
      int size = cb.readUnsignedShort();
      if(size == 0) {
         return Pair.create(Collections.emptyList(), Collections.emptyList());
      } else {
         List<String> s = new ArrayList(size);
         List<ByteBuffer> l = new ArrayList(size);

         for(int i = 0; i < size; ++i) {
            s.add(readString(cb));
            l.add(readBoundValue(cb, protocolVersion));
         }

         return Pair.create(s, l);
      }
   }

   public static InetSocketAddress readInet(ByteBuf cb) {
      int addrSize = cb.readByte() & 255;
      byte[] address = new byte[addrSize];
      cb.readBytes(address);
      int port = cb.readInt();

      try {
         return new InetSocketAddress(InetAddress.getByAddress(address), port);
      } catch (UnknownHostException var5) {
         throw new ProtocolException(String.format("Invalid IP address (%d.%d.%d.%d) while deserializing inet address", new Object[]{Byte.valueOf(address[0]), Byte.valueOf(address[1]), Byte.valueOf(address[2]), Byte.valueOf(address[3])}));
      }
   }

   public static void writeInet(InetSocketAddress inet, ByteBuf cb) {
      byte[] address = inet.getAddress().getAddress();
      cb.writeByte(address.length);
      cb.writeBytes(address);
      cb.writeInt(inet.getPort());
   }

   public static int sizeOfInet(InetSocketAddress inet) {
      byte[] address = inet.getAddress().getAddress();
      return 1 + address.length + 4;
   }

   public static InetAddress readInetAddr(ByteBuf cb) {
      int addressSize = cb.readByte() & 255;
      byte[] address = new byte[addressSize];
      cb.readBytes(address);

      try {
         return InetAddress.getByAddress(address);
      } catch (UnknownHostException var4) {
         throw new ProtocolException("Invalid IP address while deserializing inet address");
      }
   }

   public static void writeInetAddr(InetAddress inetAddr, ByteBuf cb) {
      byte[] address = inetAddr.getAddress();
      cb.writeByte(address.length);
      cb.writeBytes(address);
   }

   public static int sizeOfInetAddr(InetAddress inetAddr) {
      return 1 + inetAddr.getAddress().length;
   }

   public static byte[] readRawBytes(ByteBuf cb) {
      return readRawBytes(cb, cb.readableBytes());
   }

   private static byte[] readRawBytes(ByteBuf cb, int length) {
      byte[] bytes = new byte[length];
      cb.readBytes(bytes);
      return bytes;
   }

   static {
      allocator = (ByteBufAllocator)(USE_HEAP_ALLOCATOR?new UnpooledByteBufAllocator(false):new PooledByteBufAllocator(true));
      TL_UTF8_DECODER = new FastThreadLocal<CharsetDecoder>() {
         protected CharsetDecoder initialValue() {
            return StandardCharsets.UTF_8.newDecoder();
         }
      };
      TL_CHAR_BUFFER = new FastThreadLocal();
   }
}