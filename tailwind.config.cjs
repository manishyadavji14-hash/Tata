/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: 'class',
  content: ['./web/index.html', './web/js/**/*.js'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['"Noto Sans"', '"Noto Sans Devanagari"', 'system-ui', '-apple-system', 'Segoe UI', 'Roboto', 'sans-serif'],
      },
      colors: {
        brand: {
          50: '#eef6ff', 100: '#d9eaff', 200: '#bcdaff', 300: '#8ec3ff', 400: '#59a1ff',
          500: '#337dff', 600: '#1b5ef5', 700: '#1449e1', 800: '#173cb6', 900: '#19378f',
        },
      },
      screens: { xs: '380px' },
    },
  },
  plugins: [],
};
