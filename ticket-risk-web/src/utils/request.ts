import axios from 'axios';
const request = axios.create({ baseURL: '/api', timeout: 15000 });
request.interceptors.request.use(config => { config.headers.authorization = localStorage.getItem('ticket_token') || ''; return config; });
export async function api<T = any>(url: string, method = 'get', data?: unknown): Promise<T> {
  const response = await request({ url, method, data });
  if (!response.data.success) throw new Error(response.data.errorMsg || '请求失败');
  return response.data.data;
}
export function deviceId(): string { let value = localStorage.getItem('ticket_device'); if (!value) { value = crypto.randomUUID(); localStorage.setItem('ticket_device', value); } return value; }
