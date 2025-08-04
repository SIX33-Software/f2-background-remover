const path = require('path');
const pak = require('../../packages/react-native-background-remover/package.json');

module.exports = {
  presets: ['module:@react-native/babel-preset'],
  plugins: [
    [
      'module-resolver',
      {
        extensions: ['.tsx', '.ts', '.js', '.json'],
        alias: {
          '@six33/react-native-bg-removal': path.join(
            __dirname,
            '../../packages/react-native-background-remover',
            pak.source
          ),
        },
      },
    ],
  ],
};
