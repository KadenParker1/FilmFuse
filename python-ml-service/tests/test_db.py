from psycopg.conninfo import conninfo_to_dict

from app.core.db import APPLICATION_NAME, Pool, conninfo_for


def params(url: str) -> dict:
    return conninfo_to_dict(conninfo_for(url))


def test_hosted_url_requires_tls_and_names_the_connection():
    p = params("postgresql://ml_reader.ref:pw@aws-0-eu-west-1.pooler.supabase.com:5432/postgres")
    assert p["sslmode"] == "require"
    assert p["application_name"] == APPLICATION_NAME
    assert p["connect_timeout"] == "5"
    assert p["host"] == "aws-0-eu-west-1.pooler.supabase.com"
    assert p["user"] == "ml_reader.ref"


def test_local_hosts_keep_libpq_default_tls():
    for host in ("127.0.0.1", "localhost", "[::1]"):
        assert "sslmode" not in params(f"postgresql://ml_reader:pw@{host}:55322/postgres")
    assert "sslmode" not in params("host=/var/run/postgresql dbname=postgres")


def test_settings_in_the_url_win():
    p = params(
        "postgresql://u:pw@db.example.com/postgres"
        "?sslmode=verify-full&application_name=x&connect_timeout=10"
    )
    assert (p["sslmode"], p["application_name"], p["connect_timeout"]) == ("verify-full", "x", "10")


def test_mixed_multi_host_list_counts_as_hosted():
    p = params("host=127.0.0.1,db.example.com dbname=postgres user=u")
    assert p["sslmode"] == "require"


def test_pool_is_lazy_and_close_is_safe_before_use():
    pool = Pool("postgresql://u:pw@127.0.0.1:1/postgres")
    pool.close()  # never opened: no connection attempted
    pool.close()
