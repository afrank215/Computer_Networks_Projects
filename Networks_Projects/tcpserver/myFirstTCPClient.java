import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.*;

public class myFirstTCPClient {

  private static final short END_MARKER = (short) 0xFFFF;

  private static class Pair {
    short q;
    short c;
    Pair(short q, short c) { this.q = q; this.c = c; }
  }

  private static class LineItem {
    final int itemNo;
    final String desc;
    final int unitCost;
    final int quantity;

    LineItem(int itemNo, String desc, int unitCost, int quantity) {
      this.itemNo = itemNo;
      this.desc = desc;
      this.unitCost = unitCost;
      this.quantity = quantity;
    }

    int costPerItem() {
      return unitCost * quantity;
    }
  }

  public static void main(String args[]) throws Exception {

    if (args.length != 2)
      throw new IllegalArgumentException("Parameter(s): <Destination> <Port>");

    InetAddress destAddr = InetAddress.getByName(args[0]);
    int destPort = Integer.parseInt(args[1]);

    try (Socket sock = new Socket(destAddr, destPort)) {
      System.out.println("Connected to " + destAddr.getHostAddress() + ":" + destPort);

      Scanner sc = new Scanner(System.in);

      short requestNumber = 100;
      List<Pair> pairs = new ArrayList<>();

      while (true) {
        System.out.print("Enter quantity q1 (-1 to finish): ");
        int q1 = sc.nextInt();
        if (q1 == -1) break;
        if (q1 < 0 || q1 > 32767) {
          System.out.println("q1 must be between 0 and 32767.");
          continue;
        }

        System.out.print("Enter code c1: ");
        int c1 = sc.nextInt();
        if (c1 < 0 || c1 > 32767) {
          System.out.println("c1 must be between 0 and 32767.");
          continue;
        }

        pairs.add(new Pair((short) q1, (short) c1));
      }

      byte[] A = buildRequest(requestNumber, pairs);

      System.out.println("\nBuilt request A (raw bytes length = " + A.length + ")");
      printHexBytes(A);

      OutputStream out = sock.getOutputStream();
      out.write(A);
      out.flush();
      System.out.println("Request sent.");

      InputStream in = sock.getInputStream();
      byte[] resp = readFramedMessage(in);

      System.out.println("Client received response bytes:");
      printHexBytes(resp);

      handleResponse(requestNumber, pairs, resp);
    }
  }

  private static byte[] buildRequest(short requestNumber, List<Pair> pairs) {
    int n = pairs.size();
    int tml = 6 + 4 * n;

    ByteBuffer buf = ByteBuffer.allocate(tml).order(ByteOrder.BIG_ENDIAN);
    buf.putShort(requestNumber);
    buf.putShort((short) tml);

    for (Pair p : pairs) {
      buf.putShort(p.q);
      buf.putShort(p.c);
    }

    buf.putShort(END_MARKER);
    return buf.array();
  }

  private static byte[] readFramedMessage(InputStream in) throws IOException {
    byte[] header = readFully(in, 4);
    if (header == null)
      throw new IOException("Server closed connection before sending header.");

    ByteBuffer hb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
    hb.getShort(); // req#
    int tml = hb.getShort() & 0xFFFF;

    if (tml < 4)
      throw new IOException("Invalid TML received: " + tml);

    byte[] rest = readFully(in, tml - 4);
    if (rest == null)
      throw new IOException("Server closed connection before full response arrived.");

    byte[] full = new byte[tml];
    System.arraycopy(header, 0, full, 0, 4);
    System.arraycopy(rest, 0, full, 4, tml - 4);
    return full;
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

  private static void handleResponse(short expectedReqNo, List<Pair> requestPairs, byte[] resp) {
    ByteBuffer buf = ByteBuffer.wrap(resp).order(ByteOrder.BIG_ENDIAN);

    // Error response format: Req# (2) | -1 (2)
    if (resp.length == 4) {
      short r = buf.getShort();
      short trailer = buf.getShort();
      if (trailer == END_MARKER) {
        System.out.println("Server returned ERROR response for Request#: " + (r & 0xFFFF));
        return;
      }
      buf.rewind();
    }

    short respReqNo = buf.getShort();
    int tml = buf.getShort() & 0xFFFF;

    if (respReqNo != expectedReqNo) {
      System.out.println("Warning: response Request# mismatch. Expected " +
          (expectedReqNo & 0xFFFF) + " got " + (respReqNo & 0xFFFF));
    }
    if (tml != resp.length) {
      System.out.println("Warning: response TML mismatch. Field=" + tml + " actual=" + resp.length);
    }

    if (buf.remaining() < 4) {
      System.out.println("Error: response missing TC.");
      return;
    }
    int tc = buf.getInt();

    List<LineItem> items = new ArrayList<>();
    Charset charset = Charset.defaultCharset();
    int itemNo = 1;

    while (true) {
      if (buf.remaining() < 2) {
        System.out.println("Error: response ended unexpectedly (missing trailer).");
        return;
      }

  
      if (buf.remaining() == 2) {
        short trailer = buf.getShort();
        if (trailer != END_MARKER) {
          System.out.println("Error: invalid trailer (expected 0xFF 0xFF).");
        }
        break;
      }

      int li = buf.get() & 0xFF;

      if (buf.remaining() < li + 2 + 2) {
        System.out.println("Error: truncated item in response.");
        return;
      }

      byte[] descBytes = new byte[li];
      buf.get(descBytes);
      String desc = new String(descBytes, charset);

      short csShort = buf.getShort();
      short qShort = buf.getShort();

      int unitCost = csShort;
      int quantity = qShort & 0xFFFF;

      items.add(new LineItem(itemNo, desc, unitCost, quantity));
      itemNo++;
    }

    long computed = 0;
    for (LineItem it : items) {
      computed += (long) it.unitCost * (long) it.quantity;
    }

    if (computed != (long) tc) {
      System.out.println("Error: the total cost in the response does not match the total computed by the client.");
      return;
    }

    printBill(items, tc);
  }

  private static void printBill(List<LineItem> items, int tc) {
    System.out.println();
    System.out.println("Item #\tDescription\tUnit Cost\tQuantity\tCost Per Item");
    for (LineItem it : items) {
      System.out.println(it.itemNo + "\t" + it.desc + "\t$" + it.unitCost +
          "\t\t" + it.quantity + "\t\t$" + it.costPerItem());
    }
    System.out.println("\t\t\t\tTotal\t\t$" + tc);
    System.out.println();
  }

  private static void printHexBytes(byte[] a) {
    StringBuilder sb = new StringBuilder();
    for (byte b : a) {
      sb.append(String.format("0x%02X ", b & 0xFF));
    }
    System.out.println(sb.toString().trim());
  }
}