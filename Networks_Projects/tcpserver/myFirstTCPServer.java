import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.*;

public class myFirstTCPServer {

  private static final short END_MARKER = (short) 0xFFFF;
  private static final String DATA_FILE = "data.csv";

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

  ServerSocket servSock = new ServerSocket(port);
  System.out.println("Server listening on port " + port);

  while (true) {
    Socket clntSock = servSock.accept();
    System.out.println("Client connected from " +
        clntSock.getInetAddress().getHostAddress() + ":" + clntSock.getPort());

    try (Socket s = clntSock) {
      InputStream in = s.getInputStream();
      OutputStream out = s.getOutputStream();

      while (true) {
        byte[] header = readFully(in, 4);
        if (header == null) {
          System.out.println("Client disconnected.");
          break;
        }

        ByteBuffer hb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        short reqNo = hb.getShort();
        int tml = hb.getShort() & 0xFFFF;

        if (tml < 6) {
          System.out.println("Invalid TML (too small). Sending error response.");
          byte[] err = buildErrorResponse(reqNo);
          printHex("Server sending ERROR response bytes:", err);
          out.write(err);
          out.flush();
          continue;
        }

        byte[] rest = readFully(in, tml - 4);
        if (rest == null) {
          System.out.println("Client disconnected before full request arrived. Sending error response.");
          byte[] err = buildErrorResponse(reqNo);
          printHex("Server sending ERROR response bytes:", err);
          out.write(err);
          out.flush();
          break;
        }

        byte[] request = new byte[tml];
        System.arraycopy(header, 0, request, 0, 4);
        System.arraycopy(rest, 0, request, 4, tml - 4);

        printHex("Server received request bytes:", request);

        //Parse request pairs
        List<Pair> pairs;
        try {
          pairs = parsePairs(request);
        } catch (IllegalArgumentException ex) {
          System.out.println("Parse error: " + ex.getMessage() + " Sending error response.");
          byte[] err = buildErrorResponse(reqNo);
          printHex("Server sending ERROR response bytes:", err);
          out.write(err);
          out.flush();
          continue;
        }

        //Build real response
        byte[] response = buildResponse(reqNo, pairs, catalog);
        printHex("Server built response bytes:", response);

        out.write(response);
        out.flush();
      }
    } catch (IOException e) {
      System.out.println("Connection error: " + e.getMessage());
    }
  }
}

  //Request format: Req# (2) | TML (2) | Q1 (2) C1 (2) ... | -1 (2)
  private static List<Pair> parsePairs(byte[] request) {
    ByteBuffer buf = ByteBuffer.wrap(request).order(ByteOrder.BIG_ENDIAN);

    buf.getShort(); //req#
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
  // Req# (2) | TML (2) | TC (4) | [L1 (1) D1 (L1) CS1 (2) Q1 (2)]* | -1 (2)
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

  //Error response
  private static byte[] buildErrorResponse(short reqNo) {
    ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
    buf.putShort(reqNo);
    buf.putShort(END_MARKER);
    return buf.array();
  }

  private static Map<Short, Product> loadCatalog(String filename) {
    Map<Short, Product> map = new HashMap<>();
    try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
      String line;
      while ((line = br.readLine()) != null) {
        String s = line.trim();
        if (s.isEmpty()) continue;

        // Split into 3 parts max
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

  private static byte[] readFully(InputStream in, int len) throws IOException {
    byte[] buf = new byte[len];
    int off = 0;
    while (off < len) {
      int r = in.read(buf, off, len - off);
      if (r == -1) return null;
      off += r;
    }
    return buf;
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
