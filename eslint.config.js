const js = require('@eslint/js');
const tsParser = require('@typescript-eslint/parser');
const tsPlugin = require('@typescript-eslint/eslint-plugin');

// ESLint 9 Flat Config with TypeScript Support
module.exports = [
  // Global ignores
  {
    ignores: [
      '**/lib/**',
      '**/dist/**',
      '**/node_modules/**',
      '**/android/build/**',
      '**/ios/build/**',
      '**/.yarn/**',
      '**/yarn.lock',
      '**/package-lock.json',
      // Ignore config files
      '**/eslint.config.js',
      '**/babel.config.js',
      '**/turbo.json',
    ],
  },
  
  // JavaScript files
  {
    files: ['**/*.js'],
    ...js.configs.recommended,
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: {
        // Node.js globals
        module: 'readonly',
        require: 'readonly',
        process: 'readonly',
        __dirname: 'readonly',
        console: 'readonly',
      },
    },
    rules: {
      'no-unused-vars': 'warn',
      'no-console': 'warn',
    },
  },
  
  // TypeScript files
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      parser: tsParser,
      ecmaVersion: 'latest',
      sourceType: 'module',
      parserOptions: {
        ecmaFeatures: {
          jsx: true,
        },
      },
    },
    plugins: {
      '@typescript-eslint': tsPlugin,
    },
    rules: {
      // Basic TypeScript rules that work with ESLint 9
      '@typescript-eslint/no-unused-vars': 'warn',
      '@typescript-eslint/no-explicit-any': 'warn',
      // Disable conflicting rules
      'no-unused-vars': 'off',
      'no-undef': 'off', // TypeScript handles this
      'prefer-const': 'error', // Use regular ESLint rule instead
    },
  },
];
