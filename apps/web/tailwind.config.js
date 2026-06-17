/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        display: ['"Space Grotesk"', 'sans-serif'],
        body: ['"Manrope"', 'sans-serif'],
      },
      colors: {
        ink: {
          950: '#07111f',
          900: '#0c1b33',
          800: '#142645',
          700: '#1d3863',
        },
        gold: {
          400: '#f8c56c',
          500: '#f2ae3c',
        },
        mist: {
          50: '#f8fafc',
          100: '#eef2f8',
          200: '#dde6f2',
        },
      },
      boxShadow: {
        glow: '0 0 0 1px rgba(242,174,60,0.18), 0 18px 60px rgba(7,17,31,0.35)',
      },
    },
  },
  plugins: [],
};
