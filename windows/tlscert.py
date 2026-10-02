"""HTTPS 用の自己署名の証明書を作る・読み込む。

LAN 内の端末同士の通信を暗号化するための証明書で、第三者機関の署名はない。ブラウザには初回に警告が出るので、
画面に出す SHA-256 フィンガープリントと見比べて確かめたうえで許可する。
"""
import datetime
import hashlib
import ipaddress
import socket
import ssl
from pathlib import Path


def _expires(cert):
    return getattr(cert, "not_valid_after_utc", None) or cert.not_valid_after.replace(tzinfo=datetime.timezone.utc)


def ensure(folder: Path, addresses=()):
    """(cert.pem, key.pem) の場所を返す。無い・期限が近いときは作り直す"""
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID

    folder.mkdir(parents=True, exist_ok=True)
    cert_path, key_path = folder / "cert.pem", folder / "key.pem"
    if cert_path.exists() and key_path.exists():
        try:
            cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
            if _expires(cert) > datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=30):
                return cert_path, key_path
        except Exception:
            pass

    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Signage")])
    sans = [x509.DNSName("localhost"), x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]
    try:
        sans.append(x509.DNSName(socket.gethostname()))
    except Exception:
        pass
    for a in addresses:
        try:
            sans.append(x509.IPAddress(ipaddress.ip_address(a)))
        except ValueError:
            pass
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1)).not_valid_after(now + datetime.timedelta(days=3650))
            .add_extension(x509.SubjectAlternativeName(sans), critical=False)
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .sign(key, hashes.SHA256()))
    key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    try:
        key_path.chmod(0o600)
    except OSError:
        pass
    cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    return cert_path, key_path


def fingerprint(cert_path: Path) -> str:
    """証明書の SHA-256 フィンガープリント（AA:BB:… の形）"""
    der = ssl.PEM_cert_to_DER_cert(Path(cert_path).read_text())
    h = hashlib.sha256(der).hexdigest().upper()
    return ":".join(h[i:i + 2] for i in range(0, len(h), 2))


def server_context(cert_path, key_path) -> ssl.SSLContext:
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(str(cert_path), str(key_path))
    return ctx
