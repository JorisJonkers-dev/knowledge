export default {
  input: '../../client-spec/openapi/knowledge-api.json',
  output: {
    path: 'src/generated',
    module: {
      extension: '.js',
    },
  },
  plugins: ['@hey-api/typescript', '@hey-api/sdk', 'zod'],
}
