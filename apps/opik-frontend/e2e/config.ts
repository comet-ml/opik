export const API_URL = process.env.CI
  ? "http://nginx/api/v1/private/"
  : "http://localhost:5173/api/v1/private/";
