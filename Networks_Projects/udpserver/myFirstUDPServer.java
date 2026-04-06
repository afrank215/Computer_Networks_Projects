import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.*;

public class myFirstUDPServer {

  private static final short END_MARKER = (short) 0xFFFF;
  private static final String DATA_FILE = "data.csv";
  private static final int MAX_PACKET_SIZE = 65507;

  private static class Pair {
    final short q;
    final short c;
    Pair(short q, short c) { this.q = q; this.c = c; }
  }

  private static class Product {
    final String desc;
    final short unitCost;
    Product(String desc, short unitCost) { this.desc = desc; this.unitCost = unitCost; }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1)
      throw new IllegalArgumentException("Parameter(s): <Port>");

    int port = Integer.parseInt(args[0]);

    Map<Short, Product> catalog = loadCatalog(DATA_FILE);
    System.out.println("Loaded catalog entries: " + catalog.size());

    DatagramSocket sock = new DatagramSocket(port);
    System.out.println("Server listening on port " + port);

    while (true) {
      byte[] recvBuf = new byte[MAX_PACKET_SIZE];
      DatagramPacket recvPacket = new DatagramPacket(recvBuf, recvBuf.length);
      sock.receive(recvPacket);

      System.out.println("Client connected from " +
          recvPacket.getAddress().getHostAddress() + ":" + recvPacket.getPort());

      byte[] request = Arrays.copyOf(recvPacket.getData(), recvPacket.getLength());

      printHex("Server received request bytes:", request);

      short reqNo = extractRequestNumber(request);
      byte[] response;

      if (request.length < 4) {
        System.out.println("Invalid request (too short). Sending error response.");
        response = buildErrorResponse(reqNo);
      } else {
        ByteBuffer hb = ByteBuffer.wrap(request).order(ByteOrder.BIG_ENDIAN);
        reqNo = hb.getShort();
        int tml = hb.getShort() & 0xFFFF;

        if (request.length != tml || tml < 6) {
          System.out.println("Invalid TML or packet length mismatch. Sending error response.");
          response = buildErrorResponse(reqNo);
        } else {
          try {
            List<Pair> pairs = parsePairs(request);
            response = buildResponse(reqNo, pairs, catalog);
          } catch (IllegalArgumentException ex) {
            System.out.println("Parse error: " + ex.getMessage() + " Sending error response.");
            response = buildErrorResponse(reqNo);
          }
        }
      }

      printHex("Server built response bytes:", response);

      DatagramPacket sendPacket =
          new DatagramPacket(response, response.length, recvPacket.getAddress(), recvPacket.getPort());
      sock.send(sendPacket);
    }
  }

  // Request format: Req# (2) | TML (2) | Q1 (2) C1 (2) ... | -1 (2)
  private static List<Pair> parsePairs(byte[] request) {
    ByteBuffer buf = ByteBuffer.wrap(request).order(ByteOrder.BIG_ENDIAN);

    buf.getShort(); // req#
    int tml = buf.getShort() & 0xFFFF;
    if (tml != request.length) {
      throw new IllegalArgumentException("TML field does not match actual bytes");
    }

    List<Pair> pairs = new ArrayList<>();

    while (true) {
      if (buf.remaining() < 2) {
        throw new IllegalArgumentException("Missing trailer");
      }

      short q = buf.getShort();
      if (q == END_MARKER) {
        break;
      }

      if (buf.remaining() < 2) {
        throw new IllegalArgumentException("Truncated pair (missing code)");
      }

      short c = buf.getShort();
      pairs.add(new Pair(q, c));
    }

    return pairs;
  }

  // Response format:
  // Req# (2) | TML (2) | TC (4) | [Li (1) Di (Li) CSi (2) Qi (2)]* | -1 (2)
  private static byte[] buildResponse(short reqNo, List<Pair> pairs, Map<Short, Product> catalog) {
    Charset charset = Charset.defaultCharset();

    class Entry {
      final byte[] descBytes;
      final short unitCost;
      final short quantity;
      Entry(byte[] descBytes, short unitCost, short quantity) {
        this.descBytes = descBytes;
        this.unitCost = unitCost;
        this.quantity = quantity;
      }
    }

    List<Entry> entries = new ArrayList<>();
    long totalCost = 0;

    for (Pair p : pairs) {
      Product prod = catalog.get(p.c);
      String desc = (prod == null) ? "Article Not Available" : prod.desc;
      short unitCost = (prod == null) ? 0 : prod.unitCost;

      byte[] descBytes = desc.getBytes(charset);
      if (descBytes.length > 255) {
        byte[] cut = new byte[255];
        System.arraycopy(descBytes, 0, cut, 0, 255);
        descBytes = cut;
      }

      entries.add(new Entry(descBytes, unitCost, p.q));

      int qUnsigned = p.q & 0xFFFF;
      totalCost += (long) unitCost * (long) qUnsigned;
    }

    int tml = 2 + 2 + 4;
    for (Entry e : entries) {
      tml += 1 + e.descBytes.length + 2 + 2;
    }
    tml += 2;

    ByteBuffer buf = ByteBuffer.allocate(tml).order(ByteOrder.BIG_ENDIAN);
    buf.putShort(reqNo);
    buf.putShort((short) tml);
    buf.putInt((int) totalCost);

    for (Entry e : entries) {
      buf.put((byte) (e.descBytes.length & 0xFF));
      buf.put(e.descBytes);
      buf.putShort(e.unitCost);
      buf.putShort(e.quantity);
    }

    buf.putShort(END_MARKER);
    return buf.array();
  }

  // Error response per assignment: Req# (2) | -1 (2)
  private static byte[] buildErrorResponse(short reqNo) {
    ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
    buf.putShort(reqNo);
    buf.putShort(END_MARKER);
    return buf.array();
  }

  private static short extractRequestNumber(byte[] request) {
    if (request.length < 2) return 0;
    ByteBuffer buf = ByteBuffer.wrap(request).order(ByteOrder.BIG_ENDIAN);
    return buf.getShort();
  }

  private static Map<Short, Product> loadCatalog(String filename) {
    Map<Short, Product> map = new HashMap<>();
    try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
      String line;
      while ((line = br.readLine()) != null) {
        String s = line.trim();
        if (s.isEmpty()) continue;

        String[] parts = s.split(",", 3);
        if (parts.length < 3) continue;

        short code;
        short cost;
        try {
          code = Short.parseShort(parts[0].trim());
          cost = Short.parseShort(parts[2].trim());
        } catch (NumberFormatException e) {
          continue;
        }

        String desc = parts[1].trim();
        map.put(code, new Product(desc, cost));
      }
    } catch (IOException e) {
      System.out.println("Warning: could not read " + filename + " (" + e.getMessage() + ")");
      System.out.println("Server will treat all codes as not available.");
    }
    return map;
  }

  private static void printHex(String title, byte[] bytes) {
    System.out.println(title);
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) {
      sb.append(String.format("0x%02X ", b & 0xFF));
    }
    System.out.println(sb.toString().trim());
  }
}