async function closeInspector(page) { if(await page.locator('.inspector-dialog').isVisible()) await page.getByRole('button',{name:'关闭来源与运行',exact:true}).click() }
async function openInspector(page) { if(!await page.locator('.inspector-dialog').isVisible()) await page.getByRole('button',{name:'来源与运行',exact:true}).click() }
async function showSidebar(page) { if(!await page.locator('.app-sidebar').isVisible()) await page.getByRole('button',{name:'打开导航',exact:true}).click() }
async function selectSession(page,id){await closeInspector(page);await showSidebar(page);await page.locator(`[data-session-id="${id}"]`).click()}
async function openNewSession(page){await closeInspector(page);await showSidebar(page);await page.getByRole('button',{name:'新建对话',exact:true}).click()}
async function navigate(page,name){await closeInspector(page);await showSidebar(page);await page.getByRole('button',{name,exact:true}).click()}
module.exports={closeInspector,openInspector,selectSession,openNewSession,navigate}
