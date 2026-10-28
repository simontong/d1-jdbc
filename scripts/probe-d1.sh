#!/usr/bin/env bash
# Runs live checks against the throwaway D1 database. Usage: probe.sh <env file>
set -a; . "$1"; set +a
URL="https://api.cloudflare.com/client/v4/accounts/$D1_TEST_ACCOUNT_ID/d1/database/$D1_TEST_DATABASE_ID"
q() { # q <endpoint> <json body> <label>
	echo "=== $3"
	curl -s -w ' [HTTP %{http_code}]' -X POST "$URL/$1" -H "Authorization: Bearer $D1_TEST_API_TOKEN" -H "Content-Type: application/json" -d "$2" \
		| python3 -c 'import sys,json; s=sys.stdin.read(); b,_,c=s.rpartition(" [");
try:
	j=json.loads(b)
	for r in (j.get("result") or []): r.get("meta",{}).pop("timings",None); [r["meta"].pop(k,None) for k in ("served_by","served_by_region","served_by_colo","served_by_primary","duration","size_after")]
	print(json.dumps(j,separators=(",",":")), "["+c)
except Exception: print(s)'
}
q raw '{"sql":"drop table if exists t; drop table if exists u"}' "cleanup"
for p in '1' '1.5' 'null' 'true' 'false' '"abc"' '[1,2,255]' '9007199254740993' '"9007199254740993"' '{"a":1}'; do
	q raw "{\"sql\":\"select ?1 as v, typeof(?1) as t\",\"params\":[$p]}" "param $p"
done
q raw '{"sql":"select x'"'"'0102ff'"'"' as b, typeof(x'"'"'0102ff'"'"') as t, 9223372036854775807 as big, 1.0 as r, null as n"}' "blob and big int results"
q raw '{"sql":"select ?2 as two, ?1 as one","params":["a","b"]}' "?NNN params"
q raw '{"sql":"select :x as v","params":["a"]}' "named params"
q raw '{"sql":"create table t(id integer primary key, name text)"}' "create t"
q raw '{"sql":"insert into t(name) values (?),(?),(?)","params":["a","b","c"]}' "multi row insert changes/last_row_id"
q raw '{"sql":"update t set name = name || ?","params":["x"]}' "update changes"
q raw '{"batch":[{"sql":"insert into t(id,name) values (100,?)","params":["ok"]},{"sql":"insert into t(id,name) values (1,?)","params":["dup"]}]}' "batch with failing 2nd statement"
q raw '{"sql":"select count(*) c, sum(id=100) has100 from t"}' "after batch: is row 100 there?"
q raw '{"sql":"insert into t(id,name) values (200,'"'"'ok'"'"'); insert into t(id,name) values (1,'"'"'dup'"'"')"}' "multi statement sql with failing 2nd"
q raw '{"sql":"select count(*) c, sum(id=200) has200 from t"}' "after multi: is row 200 there?"
q raw '{"sql":"select 1 as a; select 2 as b, 3 as c"}' "two selects in one sql"
q raw '{"sql":"BEGIN TRANSACTION"}' "explicit begin"
q raw '{"sql":"selec 1"}' "syntax error"
q raw '{"sql":"select * from nope"}' "missing table"
q raw '{"sql":"create table u(id integer primary key);\r\ncreate trigger tr after insert on u\r\nbegin\r\n select 1;\r\nend;"}' "CRLF lowercase begin trigger"
q raw '{"sql":"create trigger tr2 after insert on t BEGIN select 1; END"}' "uppercase BEGIN trigger"
q query '{"sql":"select 1 as a, x'"'"'01ff'"'"' as b"}' "/query shape"
q raw '{"sql":"pragma table_list"}' "table_list"
q raw '{"sql":"drop table if exists t; drop table if exists u"}' "cleanup"
