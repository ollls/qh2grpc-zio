#!/bin/bash

# gRPC Test Script for GreeterService
# Tests all 4 gRPC method types (Unary-to-Unary, Unary-to-Stream, Stream-to-Unary, Stream-to-Stream)
#
# Prerequisites:
# - grpcurl installed (https://github.com/fullstorydev/grpcurl)
# - Server running on localhost:8443
# - orders.proto file in src/main/protobuf/
#
# Usage: ./test-grpc.sh

set -e  # Exit on error

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
HOST="localhost:8443"
PROTO_FILE="src/main/protobuf/orders.proto"
SERVICE="com.example.protos.Greeter"

# Check prerequisites
command -v grpcurl >/dev/null 2>&1 || {
    echo -e "${RED}Error: grpcurl is not installed.${NC}"
    echo "Install it with: brew install grpcurl (macOS) or go install github.com/fullstorydev/grpcurl/cmd/grpcurl@latest"
    exit 1
}

if [ ! -f "$PROTO_FILE" ]; then
    echo -e "${RED}Error: Proto file not found at $PROTO_FILE${NC}"
    exit 1
fi

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  gRPC GreeterService Test Suite${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Test 1: Unary to Unary - SayHello
echo -e "${YELLOW}[Test 1/4] Unary to Unary: SayHello${NC}"
echo -e "${YELLOW}Description: Single request -> Single response${NC}"
echo -e "${YELLOW}Expected: Receive a HelloReply with timestamp${NC}"
echo ""
echo -e "${BLUE}Request:${NC}"
echo '{"name": "John The Cube Jr", "number": 101}'
echo ""
echo -e "${BLUE}Response:${NC}"
grpcurl -v -insecure -proto "$PROTO_FILE" \
    -d '{"name": "John The Cube Jr", "number": 101}' \
    $HOST $SERVICE/SayHello
echo ""
echo -e "${GREEN}✓ Test 1 completed${NC}"
echo ""
echo "---"
echo ""

# Test 2: Unary to Stream - LotsOfReplies
echo -e "${YELLOW}[Test 2/4] Unary to Stream: LotsOfReplies${NC}"
echo -e "${YELLOW}Description: Single request -> Multiple streaming responses${NC}"
echo -e "${YELLOW}Expected: Receive 4 HelloReply messages with different timestamps${NC}"
echo ""
echo -e "${BLUE}Request:${NC}"
echo '{"name": "Alice", "number": 42}'
echo ""
echo -e "${BLUE}Response (streaming):${NC}"
grpcurl -v -insecure -proto "$PROTO_FILE" \
    -d '{"name": "Alice", "number": 42}' \
    $HOST $SERVICE/LotsOfReplies
echo ""
echo -e "${GREEN}✓ Test 2 completed${NC}"
echo ""
echo "---"
echo ""

# Test 3: Stream to Unary - LotsOfGreetings
echo -e "${YELLOW}[Test 3/4] Stream to Unary: LotsOfGreetings${NC}"
echo -e "${YELLOW}Description: Multiple streaming requests -> Single response${NC}"
echo -e "${YELLOW}Expected: Receive a single HelloReply with concatenated names${NC}"
echo ""
echo -e "${BLUE}Request (streaming):${NC}"
echo '{"name": "Bob"}'
echo '{"name": "Charlie"}'
echo '{"name": "Dave"}'
echo ""
echo -e "${BLUE}Response:${NC}"
grpcurl -v -insecure -proto "$PROTO_FILE" \
    -d @ $HOST $SERVICE/LotsOfGreetings <<EOF
{"name": "Bob"}
{"name": "Charlie"}
{"name": "Dave"}
EOF
echo ""
echo -e "${GREEN}✓ Test 3 completed${NC}"
echo ""
echo "---"
echo ""

# Test 4: Stream to Stream - BidiHello
echo -e "${YELLOW}[Test 4/4] Stream to Stream (Bidirectional): BidiHello${NC}"
echo -e "${YELLOW}Description: Multiple streaming requests -> Multiple streaming responses${NC}"
echo -e "${YELLOW}Expected: Receive a HelloReply for each HelloRequest with echoed names${NC}"
echo ""
echo -e "${BLUE}Request (streaming):${NC}"
echo '{"name": "Emma", "number": 1}'
echo '{"name": "Frank", "number": 2}'
echo '{"name": "Grace", "number": 3}'
echo ""
echo -e "${BLUE}Response (streaming):${NC}"
grpcurl -v -insecure -proto "$PROTO_FILE" \
    -d @ $HOST $SERVICE/BidiHello <<EOF
{"name": "Emma", "number": 1}
{"name": "Frank", "number": 2}
{"name": "Grace", "number": 3}
EOF
echo ""
echo -e "${GREEN}✓ Test 4 completed${NC}"
echo ""

# Summary
echo ""
echo -e "${BLUE}========================================${NC}"
echo -e "${GREEN}  All Tests Completed Successfully!${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo "Summary:"
echo "  ✓ Unary to Unary (SayHello)"
echo "  ✓ Unary to Stream (LotsOfReplies)"
echo "  ✓ Stream to Unary (LotsOfGreetings)"
echo "  ✓ Stream to Stream (BidiHello)"
echo ""
echo "All 4 gRPC communication patterns validated successfully!"
