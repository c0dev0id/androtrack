#!/bin/sh
# Script to set GitHub Actions secrets required for APK signing.
# Fill in the variable values below, then run: sh setsecrets.sh
#
# WARNING: Do not commit this file with real secret values.
# Delete or secure it after use.

SIGNING_KEYSTORE_BASE64=""
SIGNING_KEYSTORE_PASSWORD=""
SIGNING_KEY_ALIAS=""
SIGNING_KEY_PASSWORD=""

error=0
for var in SIGNING_KEYSTORE_BASE64 SIGNING_KEYSTORE_PASSWORD SIGNING_KEY_ALIAS SIGNING_KEY_PASSWORD; do
    eval "val=\$$var"
    if [ -z "$val" ]; then
        echo "Error: $var is not set." >&2
        error=1
    fi
done
[ "$error" -ne 0 ] && exit 1

gh secret set SIGNING_KEYSTORE_BASE64    --body "$SIGNING_KEYSTORE_BASE64"
gh secret set SIGNING_KEYSTORE_PASSWORD  --body "$SIGNING_KEYSTORE_PASSWORD"
gh secret set SIGNING_KEY_ALIAS          --body "$SIGNING_KEY_ALIAS"
gh secret set SIGNING_KEY_PASSWORD       --body "$SIGNING_KEY_PASSWORD"
