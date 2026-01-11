#!/bin/bash

print_usage() {
    echo "Usage: $0 [-t] <WEBSOCKET_URL> <OUTPUT_FILE_PATH>"
    echo "-t: Prepend each line with a timestamp."
}

add_timestamp=false

# Check for the -t switch
while getopts ":t" opt; do
    case "$opt" in
        t)
            add_timestamp=true
            ;;
        \?)
            echo "Invalid option: -$OPTARG"
            print_usage
            exit 1
            ;;
    esac
done

# Shift out parsed options
shift $((OPTIND-1))

if [ "$#" -ne 2 ]; then
    print_usage
    exit 1
fi

WEBSOCKET_URL="$1"
OUTPUT_FILE="$2"

# Check if wscat is installed
if ! command -v wscat &> /dev/null; then
    echo "wscat is not installed. Please install it using 'npm install -g wscat'."
    exit 1
fi

# Infinite loop to get data from WebSocket and append to file
while true; do
    if [ "$add_timestamp" = true ]; then
        wscat -c "$WEBSOCKET_URL" | while IFS= read -r line; do
            echo "$(date "+%Y-%m-%d %H:%M:%S") $line" >> "$OUTPUT_FILE"
        done
    else
        wscat -c "$WEBSOCKET_URL" >> "$OUTPUT_FILE"
    fi
    echo "$(date "+%Y-%m-%d %H:%M:%S") disconnected; retrying in 1 second"
    sleep 1 # Optional: To avoid hammering in case of immediate disconnects
done
