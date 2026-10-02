"""グループ（組織）コード。同じコードを持つ端末・管理画面だけが、サイネージを操作できる。

- コードは、英数字と - _ だけの 8〜32 文字（HTTP ヘッダーで送るため）。空なら、グループなし（従来どおり、PIN だけで操作できる）。
- mDNS には、コードのハッシュの一部（識別子）だけを載せる。別のグループの端末は、端末一覧に出ない。
"""
import hashlib
import re

_VALID = re.compile(r"^[A-Za-z0-9_-]{8,32}$")


def valid(code: str) -> bool:
    return bool(_VALID.match(code or ""))


def ident(code: str) -> str:
    """コードから作る、端末の見つけ合いに使う識別子（コード自体は、通信に載せない）。グループなしは空"""
    if not code:
        return ""
    return hashlib.sha256(("signage-group:" + code).encode()).hexdigest()[:12]
