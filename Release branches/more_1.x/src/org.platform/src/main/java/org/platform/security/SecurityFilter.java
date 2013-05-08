/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.platform.security;
import static org.platform.security.AuthSession.HttpSessionAuthSessionSetName;
import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.platform.Assert;
import org.platform.Platform;
import org.platform.context.AppContext;
import org.platform.context.ViewContext;
import org.platform.event.EventManager;
import org.platform.startup.RuntimeListener;
/**
 * Ȩ��ϵͳURL������֧�֡�
 * @version : 2013-4-9
 * @author ������ (zyc@byshell.org)
 */
class SecurityFilter implements Filter {
    private AppContext               appContext            = null;
    private SecuritySettings         settings              = null;
    private SecurityContext          secContext            = null;
    private LoginProcess             loginSecurityProcess  = null;
    private LogoutProcess            logoutSecurityProcess = null;
    private TestURLPermissionProcess urlPermissionProcess  = null;
    private AutoLoginProcess         autoLoginProcess      = null;
    //
    /**��ʼ��*/
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        Platform.info("init SecurityFilter...");
        ServletContext servletContext = filterConfig.getServletContext();
        this.appContext = (AppContext) servletContext.getAttribute(RuntimeListener.AppContextName);
        Assert.isNotNull(this.appContext, "AppContext is null.");
        //
        this.settings = this.appContext.getInstance(SecuritySettings.class);
        this.secContext = this.appContext.getInstance(SecurityContext.class);
        this.loginSecurityProcess = this.appContext.getInstance(LoginProcess.class);
        this.logoutSecurityProcess = this.appContext.getInstance(LogoutProcess.class);
        this.urlPermissionProcess = this.appContext.getInstance(TestURLPermissionProcess.class);
        this.autoLoginProcess = this.appContext.getInstance(AutoLoginProcess.class);
    }
    //
    /**����*/
    @Override
    public void destroy() {}
    //
    /***/
    private void writeAuthSession(ViewContext viewContext, boolean reWriteCookie) throws SecurityException {
        AuthSession[] authSessions = this.secContext.getCurrentAuthSession();
        //1.д��HttpSession
        StringBuilder authSessionIDs = new StringBuilder("");
        for (AuthSession authSession : authSessions)
            authSessionIDs.append(authSession.getSessionID() + ",");
        viewContext.getHttpSession(true).setAttribute(HttpSessionAuthSessionSetName, authSessionIDs.toString());
        if (reWriteCookie == true)
            this.autoLoginProcess.recoverCookie(this.secContext, viewContext);
    }
    //
    /***/
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        /*����״̬*/
        if (this.settings.isEnableURL() == false) {
            chain.doFilter(request, response);
            return;
        }
        /*ִ�д���*/
        this.doSecurityFilter((HttpServletRequest) request, (HttpServletResponse) response, chain);
        /*�ۻ��̵߳�AuthSession������ˢ������*/
        AuthSession[] authSessions = secContext.getCurrentAuthSession();
        for (AuthSession authSession : authSessions) {
            secContext.inactivationAuthSession(authSession.getSessionID()); /*�ۻ�AuthSession*/
            authSession.refreshCacheTime();/*ˢ�»����е�����*/
        }
    }
    //
    /***/
    public void doSecurityFilter(HttpServletRequest httpRequest, HttpServletResponse httpResponse, FilterChain chain) throws IOException, ServletException {
        ViewContext viewContext = ViewContext.currentViewContext();
        //1.�ָ��Ự
        try {
            this.autoLoginProcess.recoverCookie(secContext, viewContext);
            this.writeAuthSession(viewContext, false);
        } catch (SecurityException e) {
            Platform.error("recover AuthSession failure!%s", e);
        }
        //2.������
        String reqPath = viewContext.getRequestURI();
        if (reqPath.endsWith(this.settings.getLoginURL()) == true) {
            /*A.����*/
            SecurityForward forward = this.loginSecurityProcess.processLogin(this.secContext, viewContext);
            this.writeAuthSession(viewContext, true);
            if (forward != null)
                forward.forward(viewContext);//��ת��ַ
            return;
        }
        if (reqPath.endsWith(this.settings.getLogoutURL()) == true) {
            /*B.�ǳ�*/
            SecurityForward forward = this.logoutSecurityProcess.processLogout(this.secContext, viewContext);
            this.writeAuthSession(viewContext, true);
            if (forward != null)
                forward.forward(viewContext);//��ת��ַ
            return;
        }
        //3.��������
        try {
            AuthSession[] authSessions = this.secContext.getCurrentAuthSession();
            EventManager eventManager = this.secContext.getAppContext().getInstance(EventManager.class);
            eventManager.throwEvent(SecurityEvent.Security_URLPermissionEvent, reqPath, authSessions);/*�׳��¼�*/
            boolean res = this.urlPermissionProcess.testURL(this.secContext, authSessions, viewContext);
            if (res == false)
                throw new PermissionException(reqPath);
            chain.doFilter(httpRequest, httpResponse);
        } catch (PermissionException e) {
            Platform.debug("testPermission failure! uri=%s%s", reqPath, e);/*û��Ȩ��*/
            SecurityDispatcher dispatcher = this.secContext.getDispatcher(reqPath);
            if (dispatcher != null)
                dispatcher.forwardFailure(e).forward(viewContext);
            else
                e.printStackTrace(httpResponse.getWriter());
        }
    }
}