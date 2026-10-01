"""Déchiffre un fichier de données de l'app HIIT (séance ou santé).

Format : {"enc": "aes-256-gcm+gzip", "v": 1, "iv": b64, "ct": b64}, écrit par la PWA
(WebCrypto) et par HIIT Bridge (Kotlin). Les fichiers en clair sont renvoyés tels quels.

Usage : HIIT_DATA_KEY=<clé base64> python analysis/decrypt.py data/sessions/xxx.json > clair.json
La clé se copie depuis l'app : Réglages → Chiffrement des données → Copier la clé.
"""
import base64, gzip, json, os, sys

from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def load(path, key_b64=None):
    with open(path, encoding="utf-8") as f:
        obj = json.load(f)
    if obj.get("enc") != "aes-256-gcm+gzip":
        return obj
    key = base64.b64decode(key_b64 or os.environ["HIIT_DATA_KEY"])
    plain = AESGCM(key).decrypt(base64.b64decode(obj["iv"]), base64.b64decode(obj["ct"]), None)
    return json.loads(gzip.decompress(plain).decode("utf-8"))


if __name__ == "__main__":
    json.dump(load(sys.argv[1]), sys.stdout, ensure_ascii=False)
