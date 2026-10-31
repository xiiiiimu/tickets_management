"""Bounded cleanup of an exclusively claimed test DB; never delete an unowned DB."""
def cleanup_owned_redis(redis, owner):
    marker='credit-risk:controlled-owner'
    assert redis.command('GET',marker)==owner,'Redis test ownership changed'
    keys=redis.command('KEYS','*')
    assert all(k.startswith((marker,'ticket:','risk:','login:')) for k in keys),'Unexpected key; no cleanup'
    live=[key for key in keys if key!=marker]
    if live:redis.command('UNLINK',*live)
    # DBSIZE includes physical entries whose TTL elapsed but active expiry has not
    # visited yet. SCAN visits those entries; it must not reveal new live writers.
    cursor='0'
    for _ in range(100):
        cursor,remaining=redis.command('SCAN',cursor,'COUNT',200)
        assert all(key==marker for key in remaining),'Live test writer during cleanup'
        if cursor=='0':break
    else:raise AssertionError('Bounded cleanup scan exhausted')
    assert redis.command('GET',marker)==owner
    assert redis.command('DBSIZE')==1,'Residual entries after cleanup scan'
    redis.command('UNLINK',marker)
    left=redis.command('DBSIZE');assert left==0,'Unexpected writer after ownership removal'
    return left
