#!/bin/bash
# Example skill execution script
# This demonstrates how skills can include executable logic

echo "Example Skill executed at: $(date)"
echo "Arguments: $@"
echo "Project directory: ${CLAWHUB_WORKDIR:-/home/z/my-project}"

# Your skill logic goes here
# Exit 0 for success, non-zero for failure
exit 0
