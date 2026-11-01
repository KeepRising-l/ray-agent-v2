/** 独立设计预览：复用已安装的Vue/Vite，不连接任何业务后端。 */
const path = require('node:path')
const { pathToFileURL } = require('node:url')
const web = path.resolve(__dirname, '../../ray-agent-web')

async function main() {
  const { createServer } = await import(pathToFileURL(require.resolve('vite', { paths: [web] })))
  const vue = (await import(pathToFileURL(require.resolve('@vitejs/plugin-vue', { paths: [web] })))).default
  const server = await createServer({
    root: __dirname,
    configFile: false,
    plugins: [vue()],
    cacheDir: path.join(web, 'node_modules/.vite-ui-preview'),
    resolve: { alias: [{ find: /^vue$/, replacement: path.join(web, 'node_modules/vue/dist/vue.runtime.esm-bundler.js') }] },
    server: { host: '127.0.0.1', port: 5175, strictPort: true, fs: { allow: [path.resolve(__dirname, '../..')] } },
  })
  await server.listen()
  console.log('RayAgent design preview: http://127.0.0.1:5175')
}
main().catch(error => { console.error(error.message); process.exitCode = 1 })
