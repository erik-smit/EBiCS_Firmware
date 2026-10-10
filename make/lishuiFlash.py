import serial
import time
import sys
import os
import logging
import argparse

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
logger = logging.getLogger("LishuiFlash")

def log_data(direction, data):
    """Logs serial data in hex format if verbose is enabled."""
    if not data:
        return
    hex_data = data.hex(' ')
    logger.debug(f"{direction}: {hex_data}")

def check_response(ser, verbose=False):
    """Reads all available data from the serial port and logs it if present.
    Returns True if an error response (ER / 0x45 0x52) is detected.
    """
    response = ser.read_all()
    if response:
        if verbose:
            log_data("INT-READ", response)
        
        # Check for error "ER" (0x45 0x52)
        if b"ER" in response or b"\x45\x52" in response:
            logger.error("Controller reported ERROR ('ER'). Aborting flash!")
            return True
            
        try:
            text = response.decode('ascii', errors='replace').strip()
            if text:
                logger.info(f"Controller output: {text}")
        except:
            pass
    return False

def flash_lishui(port, file_path, verbose=False):
    baudrate = 38400
    
    if verbose:
        logger.setLevel(logging.DEBUG)
        logger.debug("Verbose mode enabled")

    try:
        # Config: 38400 baud, 8 data bits, no parity, 1 stop bit (standard)
        with serial.Serial(port, baudrate, parity=serial.PARITY_NONE, stopbits=serial.STOPBITS_ONE, bytesize=serial.EIGHTBITS, timeout=0.1) as ser:
            logger.info(f"Opening {port} at {baudrate} baud...")
            
            logger.info("Power on controller now")
            
            # Wait until "MPC" is read
            while True:
                response = ser.read_until(b"MPC")
                if response and verbose: log_data("READ", response)
                    
                if b"MPC" in response:
                    logger.info("Received MPC")
                    break
            
            # Wait 1 millisecond
            time.sleep(0.001)
            
            # Write byte 0x44, 0x14 (20) times
            logger.info("Sending handshake sequence...")
            handshake = bytes([0x44] * 0x14)
            ser.write(handshake)
            if verbose: log_data("WRITE", handshake)
            if check_response(ser, verbose): return # Abort if ER
            
            # Sleep 100ms
            time.sleep(0.1)
            
            # Read file line by line
            logger.info(f"Flashing file: {file_path}")
            with open(file_path, 'r') as f:
                lines = [line.strip() for line in f if line.strip()]
                
            total_lines = len(lines)
            for i, line in enumerate(lines):
                try:
                    data = bytes.fromhex(line)
                    if len(data) != 0x14:
                        logger.warning(f"Line {i+1} length is {len(data)}, expected {0x14}")
                except ValueError:
                    logger.error(f"Error parsing hex on line {i+1}: {line}")
                    continue
                
                ser.write(data)
                if verbose: log_data("WRITE", data)
                if check_response(ser, verbose): 
                    logger.error(f"Abort triggered at line {i+1}/{total_lines}")
                    return # Abort if ER detected
                
                # controller replies with "ER" if writing too fast
                time.sleep(0.010)
            
            # When done, write byte 0x55, 0x14 (20) times
            logger.info("Finalizing flash...")
            final_seq = bytes([0x55] * 0x14)
            ser.write(final_seq)
            if verbose: log_data("WRITE", final_seq)
            
            # Wait for "OK" for up to a second
            logger.info("Waiting for 'OK' response (max 1s)...")
            start_time = time.time()
            ok_received = False
            while time.time() - start_time < 1.0:
                response = ser.read_all()
                if response:
                    if verbose: log_data("READ", response)
                    try:
                        text = response.decode('ascii', errors='replace').strip()
                        if "OK" in text:
                            logger.info("Flash successful! Controller replied: OK")
                            ok_received = True
                            break
                        elif text:
                            logger.info(f"Controller: {text}")
                    except:
                        if not verbose:
                            log_data("READ-HEX", response)
                time.sleep(0.05)
            
            if not ok_received:
                logger.warning("Flash finished, but 'OK' response was not received within 1 second.")

    except serial.SerialException as e:
        logger.error(f"Serial Error: {e}")
    except FileNotFoundError:
        logger.error(f"File Error: {file_path} not found.")
    except Exception as e:
        logger.error(f"Unexpected Error: {e}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Lishui Firmware Flashing Script")
    parser.add_argument("port", help="Serial COM port (e.g., COM3 or /dev/ttyUSB0)")
    parser.add_argument("file", help="Path to the .lsh firmware file")
    parser.add_argument("-v", "--verbose", action="store_true", help="Enable verbose output showing all serial traffic")
    
    args = parser.parse_args()
    
    if not os.path.exists(args.file):
        logger.error(f"Error: File {args.file} does not exist.")
        sys.exit(1)
        
    flash_lishui(args.port, args.file, verbose=args.verbose)
