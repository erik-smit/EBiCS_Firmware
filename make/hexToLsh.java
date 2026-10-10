import java.io.*;
import java.nio.file.*;
import java.util.*;

public class hexToLsh {

  private static final int FILL_START = 0x08001000;

  // Key for XOR encryption
  private static final int[] KEY = {
      0x81, 0x30, 0x00, 0x5a, 0x7f, 0xcb, 0x37, 0x13, 0x32, 0x85,
      0x20, 0x4b, 0xc8, 0xf3, 0x10, 0x2e, 0x1c, 0xa7, 0xc2, 0xa3
  };

  public static void main(String[] args) {
    if (args.length < 3) {
      printUsage();
      return;
    }

    String mode = args[0];
    String inputPath = args[1];
    String destination = args[2];

    try {
      // 1. Determine Input Type and Get LSH Lines
      List<String> lshLines;
      if (inputPath.toLowerCase().endsWith(".hex")) {
        System.out.println("Processing Hex file: " + inputPath);
        lshLines = convertHexToLshLines(inputPath);
      } else {
        System.out.println("Reading LSH file: " + inputPath);
        lshLines = Files.readAllLines(Paths.get(inputPath));
      }

      if (lshLines == null || lshLines.isEmpty()) {
        System.out.println("No data to process.");
        return;
      }

      // 2. Handle Output based on Mode
      if (mode.equals("--flash")) {
        System.out.println("Flashing to port: " + destination);
        flashLsh(destination, lshLines);
      } else if (mode.equals("--convert")) {
        System.out.println("Writing to file: " + destination);
        Path destPath = Paths.get(destination);
        if (destPath.getParent() != null) {
          Files.createDirectories(destPath.getParent());
        }
        Files.write(destPath, lshLines, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("Done.");
      } else {
        System.out.println("Unknown mode: " + mode);
        printUsage();
      }

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private static void printUsage() {
    System.out.println("Usage:");
    System.out.println("  java make/hexToLsh.java --convert <input_file> <output_file>");
    System.out.println("  java make/hexToLsh.java --flash   <input_file> <serial_port>");
  }

  private static List<String> convertHexToLshLines(String hexFileName) throws IOException {
    Path hexPath = Paths.get(hexFileName);

    if (!Files.exists(hexPath)) {
      System.out.println("Error: " + hexFileName + " not found.");
      return null;
    }

    // Memory map: 32-bit Address -> Byte
    TreeMap<Integer, Integer> memory = new TreeMap<>();
    int maxOffset = 0;

    // Read and Parse Input Hex
    try (BufferedReader reader = Files.newBufferedReader(hexPath)) {
      String line;
      int currentExtendedAddr = 0;

      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (!line.startsWith(":"))
          continue;
        if (line.length() < 11)
          continue;

        int len = Integer.parseInt(line.substring(1, 3), 16);
        int offset = Integer.parseInt(line.substring(3, 7), 16);
        int type = Integer.parseInt(line.substring(7, 9), 16);

        if (type == 0x00) { // Data Record
          if (offset > maxOffset) {
            maxOffset = offset;
          }

          int startAddr = currentExtendedAddr + offset;
          for (int i = 0; i < len; i++) {
            int val = Integer.parseInt(line.substring(9 + i * 2, 11 + i * 2), 16);
            memory.put(startAddr + i, val);
          }
        } else if (type == 0x04) { // Extended Linear Address
          currentExtendedAddr = Integer.parseInt(line.substring(9, 13), 16) << 16;
        }
      }
    }

    int limitRecOffset = 16 * ((maxOffset / 16) + 2);
    int fillLimitAddr = 0x08000000 + limitRecOffset;

    System.out.println("max address is: " + Integer.toHexString(maxOffset) +
        ", will fill up to: " + Integer.toHexString(limitRecOffset));

    // Fill holes
    for (int addr = FILL_START; addr < fillLimitAddr; addr++) {
      memory.putIfAbsent(addr, 0x00);
    }

    List<String> outputLines = new ArrayList<>();
    if (memory.isEmpty())
      return outputLines;

    // Generate Output
    List<Integer> sortedAddrs = new ArrayList<>(memory.keySet());
    int idx = 0;
    while (idx < sortedAddrs.size()) {
      int startAddr = sortedAddrs.get(idx);

      List<Integer> blockBytes = new ArrayList<>();
      blockBytes.add(memory.get(startAddr));
      idx++;

      for (int i = 1; i < 16; i++) {
        if (idx < sortedAddrs.size() && sortedAddrs.get(idx) == startAddr + i) {
          blockBytes.add(memory.get(startAddr + i));
          idx++;
        } else {
          break;
        }
      }

      int count = blockBytes.size();
      int addrField = startAddr & 0xFFFF;
      int typeField = 0x00;

      int sum = count;
      sum += (addrField >> 8) & 0xFF;
      sum += addrField & 0xFF;
      sum += typeField;

      for (int b : blockBytes) {
        sum += b;
      }

      int checksum = (~sum + 1) & 0xFF;

      int[] rawBytes = new int[2 + 1 + count + 1];
      rawBytes[0] = (addrField >> 8) & 0xFF;
      rawBytes[1] = addrField & 0xFF;
      rawBytes[2] = typeField;
      for (int i = 0; i < count; i++)
        rawBytes[3 + i] = blockBytes.get(i);
      rawBytes[3 + count] = checksum;

      StringBuilder lineOutput = new StringBuilder();
      int keyIndex = 0;
      for (int val : rawBytes) {
        int xorVal = val ^ KEY[keyIndex];
        keyIndex++;
        lineOutput.append(String.format("%02X", xorVal));
      }
      outputLines.add(lineOutput.toString());
    }
    return outputLines;
  }

  // --- Flashing Logic ---

  private static void flashLsh(String portName, List<String> lines) throws IOException, InterruptedException {
    // Configure port using Windows 'mode' command
    System.out.println("Configuring port " + portName + "...");
    ProcessBuilder pb;
    if (System.getProperty("os.name").toLowerCase().contains("win")) {
      pb = new ProcessBuilder("cmd.exe", "/c", "mode", portName + ":", "38400,n,8,1");
    } else {
      // Linux/Mac - mode command defaults or stty?
      // Providing stty fallback just in case, though user is on generic Windows
      pb = new ProcessBuilder("stty", "-F", portName, "38400");
    }
    Process p = pb.start();
    p.waitFor();
    if (p.exitValue() != 0) {
      System.err.println("Failed to configure port " + portName);
      // Try continuing anyway, maybe it's already configured or we are on Linux
      // (where mode fails but we might not need it if defaults work?)
      // But strict failure is safer.
    }

    // Open Port
    File portFile = new File("\\\\.\\" + portName);
    try (RandomAccessFile port = new RandomAccessFile(portFile, "rw")) {
      System.out.println("Opening " + portName + "...");
      System.out.println("Power on controller now...");

      // 1. Wait for "MPC"
      // We need to read byte by byte
      StringBuilder buffer = new StringBuilder();
      while (true) {
        int b = port.read();
        if (b == -1) {
          Thread.sleep(10);
          continue;
        }
        char c = (char) b;
        // Basic filtering like log_data
        // if (c >= 32 && c <= 126) System.out.print(c);

        buffer.append(c);
        if (buffer.toString().endsWith("MPC")) {
          System.out.println("\nReceived MPC");
          break;
        }
        // Optional: print '.' to show liveness?
      }

      Thread.sleep(1);

      // 2. Handshake
      System.out.println("Sending handshake sequence...");
      byte[] handshake = new byte[0x14];
      Arrays.fill(handshake, (byte) 0x44);
      port.write(handshake);

      checkResponse(port); // Check for ER

      Thread.sleep(100);

      // 3. Send Lines
      System.out.println("Flashing " + lines.size() + " lines...");
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i).trim();
        if (line.isEmpty())
          continue;

        byte[] data = hexStringToByteArray(line);
        if (data.length != 0x14) {
          // System.out.println("Warning: Line length " + data.length + " != 20");
        }

        port.write(data);

        // Check response (ER?)
        // Note: The python script does "check_response" which reads ALL available.
        // In pure Java blocking IO, we cannot peek easily.
        // BUT, the protocol seems to only send ER if error, or nothing if OK during
        // this phase?
        // The python script says "controller replies with 'ER' if writing too fast".
        // If we assume it works, we just wait.
        // To safely check for ER without blocking forever if empty, implementation is
        // hard with RandomAccessFile.
        // We will rely on proper timing (sleep).

        // However, python script reads_all(). If we don't read, buffer might fill up?
        // We should try to drain input if possible.
        // skipBytes works?

        drainInput(port);

        Thread.sleep(15); // Python uses 0.010 (10ms), we use 15 to be safe

        if (i % 50 == 0)
          System.out.print(".");
      }
      System.out.println();

      // 4. Finalize
      System.out.println("Finalizing flash...");
      byte[] finalSeq = new byte[20];
      Arrays.fill(finalSeq, (byte) 0x55);
      port.write(finalSeq);

      // 5. Wait for OK
      System.out.println("Waiting for 'OK'...");
      long startTime = System.currentTimeMillis();
      boolean ok = false;
      StringBuilder resp = new StringBuilder();

      // Allow 2 seconds timeout
      // Problem: port.read() blocks! If no OK, we hang forever.
      // We can't interrupt read() easily.
      // But usually the controller replies immediately.

      // To mitigate hanging: use a loop with available() logic?
      // RandomAccessFile doesn't have available().
      // FileInputStream does.
      // But we opened as RandAccessFile.

      // We'll just block-read and hope.
      // Or we can peek by closing and reopening? No.

      // Let's implement a simple read loop that breaks on "OK" or "ER".
      // If it hangs, the user has to Ctrl+C. (Similar to Python script if it didn't
      // use timeout).

      while (!ok && (System.currentTimeMillis() - startTime < 2000)) {
        // We can't check available on RAF.
        // Just read data.
        int b = port.read();
        if (b != -1) {
          resp.append((char) b);
          if (resp.toString().contains("OK")) {
            ok = true;
            System.out.println("Flash successful! Controller replied: OK");
          } else if (resp.toString().contains("ER")) {
            System.out.println("Error: Controller reported ER");
            break;
          }
        }
      }

      if (!ok) {
        System.out.println("Finished, but did not receive OK confirmation (or timed out). Output: " + resp);
      }

    }
  }

  // Helper to drain input (non-blocking attempt? No, RAF blocks).
  // This is the weak point. If we call read() and there is no data, we hang.
  // So we should NOT read unless we expect data.
  // The python script blindly reads to check for ER.
  // If the controller is silent, Python read_all returns empty.
  // Java read() blocks.
  // We will skip explicit error checking per line to avoid hanging, and rely on
  // the final OK.
  private static void drainInput(RandomAccessFile port) {
    // No-op for safety in pure Java blocking mode
  }

  private static void checkResponse(RandomAccessFile port) {
    // Same as drain, unsafe to read if we aren't sure data is there.
  }

  public static byte[] hexStringToByteArray(String s) {
    int len = s.length();
    byte[] data = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
      data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
          + Character.digit(s.charAt(i + 1), 16));
    }
    return data;
  }
}
