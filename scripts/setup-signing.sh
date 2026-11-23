#!/bin/sh
# Creates the release signing key (once) and android/keystore.properties, which the build reads.
# The password is typed here, hidden; it is kept only in that file (left out of git) and in your
# memory. KEEP A COPY OF THE KEY FILE: without it, no update of the app can be released.
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
KEYTOOL=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}/bin/keytool
DIR="$HOME/Documents/OpenMoHAA Android - chave de assinatura"
STORE="$DIR/openmohaa-android-release.jks"
PROPS="$ROOT/android/keystore.properties"

if [ -e "$STORE" ]; then
    echo "A chave já existe: $STORE"
    exit 1
fi
mkdir -p "$DIR"

printf 'Crie uma senha para a chave (mínimo 6 caracteres, sem espaços): '
stty -echo; read -r PW; stty echo; echo
printf 'Repita a senha: '
stty -echo; read -r PW2; stty echo; echo
if [ "$PW" != "$PW2" ]; then echo "As senhas não são iguais. Nada foi criado."; exit 1; fi
if [ ${#PW} -lt 6 ]; then echo "A senha precisa ter pelo menos 6 caracteres. Nada foi criado."; exit 1; fi

export PW
"$KEYTOOL" -genkeypair -keystore "$STORE" -storetype PKCS12 -alias openmohaa -keyalg RSA \
    -keysize 4096 -validity 36500 -dname "CN=cricastrocoli" -storepass:env PW -keypass:env PW

umask 077
cat > "$PROPS" <<EOF
storeFile=$STORE
storePassword=$PW
keyAlias=openmohaa
keyPassword=$PW
EOF

echo
echo "Pronto. Chave criada em:"
echo "  $STORE"
echo "Guarde uma cópia desse arquivo (e da senha) fora deste Mac: pendrive, nuvem..."
