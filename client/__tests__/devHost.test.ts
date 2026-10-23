import { lanHostFromHostUri, resolveDevUrl } from '../lib/devHost';

describe('lanHostFromHostUri', () => {
  it.each([
    ['192.168.1.20:8081', '192.168.1.20'],
    ['10.0.0.5:8081', '10.0.0.5'],
    ['exp://192.168.1.20:8081', '192.168.1.20'],
    ['kadens-mac.local:8081', 'kadens-mac.local'],
  ])('%s → %s', (hostUri, host) => {
    expect(lanHostFromHostUri(hostUri)).toBe(host);
  });

  it.each([undefined, null, '', 'localhost:8081', '127.0.0.1:8081', 'abc-anonymous-8081.exp.direct'])(
    'nothing to rewrite to for %p',
    (hostUri) => {
      expect(lanHostFromHostUri(hostUri)).toBeNull();
    },
  );
});

describe('resolveDevUrl', () => {
  const lan = '192.168.1.20:8081';

  it('points localhost API and Supabase URLs at the dev machine, keeping port and path', () => {
    expect(resolveDevUrl('http://localhost:8090/api/v1', lan)).toBe('http://192.168.1.20:8090/api/v1');
    expect(resolveDevUrl('http://127.0.0.1:55321', lan)).toBe('http://192.168.1.20:55321');
    expect(resolveDevUrl('http://localhost', lan)).toBe('http://192.168.1.20');
  });

  it('leaves hosted and non-loopback URLs alone', () => {
    expect(resolveDevUrl('https://abcd.supabase.co', lan)).toBe('https://abcd.supabase.co');
    expect(resolveDevUrl('http://localhost.example.com/api', lan)).toBe('http://localhost.example.com/api');
    expect(resolveDevUrl('http://10.0.2.2:8090/api/v1', lan)).toBe('http://10.0.2.2:8090/api/v1');
  });

  it('changes nothing on web / the simulator (localhost Metro) or without Metro (release builds)', () => {
    expect(resolveDevUrl('http://localhost:8090/api/v1', 'localhost:8081')).toBe('http://localhost:8090/api/v1');
    expect(resolveDevUrl('http://localhost:8090/api/v1', undefined)).toBe('http://localhost:8090/api/v1');
    expect(resolveDevUrl('', lan)).toBe('');
  });
});

describe('env in development on a device', () => {
  const saved = { ...process.env };
  afterEach(() => {
    process.env = { ...saved };
    jest.resetModules();
    jest.dontMock('expo-constants');
  });

  function loadEnv(hostUri: string | undefined) {
    jest.doMock('expo-constants', () => ({ __esModule: true, default: { expoConfig: { hostUri } } }));
    let loaded: typeof import('../lib/env').env | undefined;
    jest.isolateModules(() => {
      loaded = (require('../lib/env') as typeof import('../lib/env')).env;
    });
    return loaded!;
  }

  it('follows Metro onto the LAN', () => {
    process.env.EXPO_PUBLIC_API_BASE_URL = 'http://localhost:8090/api/v1/';
    process.env.EXPO_PUBLIC_SUPABASE_URL = 'http://127.0.0.1:55321';
    process.env.EXPO_PUBLIC_SUPABASE_ANON_KEY = 'anon';
    const env = loadEnv('192.168.1.20:8081');
    expect(env.apiBaseUrl).toBe('http://192.168.1.20:8090/api/v1');
    expect(env.supabaseUrl).toBe('http://192.168.1.20:55321');
    expect(env.isSupabaseConfigured).toBe(true);
  });

  it('keeps localhost when Metro is local (simulator, web)', () => {
    process.env.EXPO_PUBLIC_API_BASE_URL = 'http://localhost:8090/api/v1';
    const env = loadEnv('localhost:8081');
    expect(env.apiBaseUrl).toBe('http://localhost:8090/api/v1');
  });
});
