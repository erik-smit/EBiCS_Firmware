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
    try {
      String hexFileName = "build/EBiCS_Firmware.hex";
      Path hexPath = Paths.get(hexFileName);

      if (!Files.exists(hexPath)) {
        System.out.println("Error: " + hexFileName + " not found.");
        return;
      }

      // Memory map: 32-bit Address -> Byte
      TreeMap<Integer, Integer> memory = new TreeMap<>();
      int maxOffset = 0;

      // 1. Read and Parse Input Hex
      try (BufferedReader reader = Files.newBufferedReader(hexPath)) {
        String line;
        int currentExtendedAddr = 0;

        while ((line = reader.readLine()) != null) {
          line = line.trim();
          if (!line.startsWith(":"))
            continue;
          if (line.length() < 11)
            continue; // Min length check

          int len = Integer.parseInt(line.substring(1, 3), 16);
          int offset = Integer.parseInt(line.substring(3, 7), 16);
          int type = Integer.parseInt(line.substring(7, 9), 16);

          if (type == 0x00) { // Data Record
            // Track max offset as in original script
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
          // Ignore other types (EOF 01, Seg 02, Start 03/05)
        }
      }

      // 2. Calculate Fill Limit
      // Original logic: int limitAddress = 16 * ((maxAddress / 16) + 2);
      int limitRecOffset = 16 * ((maxOffset / 16) + 2);
      // Limit Address assumes base 0x08000000 + limitRecOffset
      int fillLimitAddr = 0x08000000 + limitRecOffset;

      System.out.println("max address is: " + Integer.toHexString(maxOffset) +
          ", will fill up to: " + Integer.toHexString(limitRecOffset));

      // 3. Perform Fill
      // Fill holes between FILL_START and fillLimitAddr with 0x00
      for (int addr = FILL_START; addr < fillLimitAddr; addr++) {
        memory.putIfAbsent(addr, 0x00);
      }

      // 4. Generate Output (.lsh)
      String outputDirName = "output";
      Path outputDirPath = Paths.get(outputDirName);
      if (!Files.exists(outputDirPath)) {
        Files.createDirectory(outputDirPath);
      }

      String lshFileName = "output/EBiCS_Firmware.lsh";
      try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(lshFileName))) {

        if (memory.isEmpty())
          return;

        int lineIndex = 0;

        // Effective strategy: Iterate from min to max, respecting 16-byte block
        // structure
        List<Integer> sortedAddrs = new ArrayList<>(memory.keySet());
        int idx = 0;
        while (idx < sortedAddrs.size()) {
          int startAddr = sortedAddrs.get(idx);

          // Determine block length (max 16, and contiguous)
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

          // Generate Line

          // Calculate fields for Checksum purposes (to match logic of srec_cat generated
          // lines if we were parsing them)
          // But actually, we just need to XOR the fields.
          // The fields are: Count, AddrHi, AddrLo, Type, Data..., Checksum

          int count = blockBytes.size();
          int addrField = startAddr & 0xFFFF; // Truncate to 16 bits
          int typeField = 0x00;

          // Checksum calculation to simulate Intel Hex checksum
          int sum = count;
          sum += (addrField >> 8) & 0xFF;
          sum += addrField & 0xFF;
          sum += typeField;

          for (int b : blockBytes) {
            sum += b;
          }

          int checksum = (~sum + 1) & 0xFF; // Two's complement

          // Construct the bytes to XOR
          // Structure: AddrHi, AddrLo, Type, Data..., Checksum

          int[] rawBytes = new int[2 + 1 + count + 1];
          rawBytes[0] = (addrField >> 8) & 0xFF;
          rawBytes[1] = addrField & 0xFF;
          rawBytes[2] = typeField;
          for (int i = 0; i < count; i++)
            rawBytes[3 + i] = blockBytes.get(i);
          rawBytes[3 + count] = checksum;

          // XOR and append to output
          StringBuilder lineOutput = new StringBuilder();
          int keyIndex = 0;
          for (int val : rawBytes) {
            int xorVal = val ^ KEY[keyIndex];
            keyIndex++;
            lineOutput.append(String.format("%02X", xorVal));
          }

          // Write to file
          if (lineIndex > 0)
            writer.write("\n");
          writer.write(lineOutput.toString());
          lineIndex++;
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}