import { createRouter, type RouterHistory } from 'vue-router'
import HomePage from './pages/HomePage.vue'
import WorkbenchPage from './pages/WorkbenchPage.vue'
import ProjectsPage from './pages/ProjectsPage.vue'
import NotFoundPage from './pages/NotFoundPage.vue'
import StandalonePreviewPage from './pages/StandalonePreviewPage.vue'
import PublicAppPage from './pages/PublicAppPage.vue'
import AccountPage from './pages/AccountPage.vue'
import AccountSettingsPage from './pages/AccountSettingsPage.vue'
import WorkspaceCreatePage from './pages/WorkspaceCreatePage.vue'

export function createAppRouter(history: RouterHistory) {
  const router = createRouter({
    history,
    routes: [
      { path: '/a/:slug', component: PublicAppPage, meta: { title: '公开应用', publicApp: true } },
      { path: '/', component: HomePage, meta: { title: '开始创作', entryPage: true } },
      { path: '/create', component: WorkspaceCreatePage, meta: { title: '开始创作' } },
      { path: '/account', component: AccountPage, meta: { title: '邮箱账号', entryPage: true } },
      { path: '/settings/account', component: AccountSettingsPage, meta: { title: '账号管理' } },
      { path: '/workbench', component: WorkbenchPage, meta: { title: '工作台' } },
      { path: '/projects', component: ProjectsPage, meta: { title: '我的项目' } },
      { path: '/projects/:id', component: WorkbenchPage, meta: { title: '项目工作台' } },
      {
        path: '/preview/:id/:versionId',
        component: StandalonePreviewPage,
        meta: { title: '私有预览' },
      },
      { path: '/:pathMatch(.*)*', component: NotFoundPage, meta: { title: '页面不存在' } },
    ],
    scrollBehavior: () => ({ top: 0 }),
  })
  router.afterEach((route, _from, failure) => {
    if (failure) return
    document.title = `${String(route.meta.title)} · Atoms Demo Studio`
  })
  return router
}
