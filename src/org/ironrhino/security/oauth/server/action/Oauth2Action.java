package org.ironrhino.security.oauth.server.action;

import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.struts2.ServletActionContext;
import org.ironrhino.core.event.EventPublisher;
import org.ironrhino.core.metadata.AutoConfig;
import org.ironrhino.core.metadata.JsonConfig;
import org.ironrhino.core.metadata.Scope;
import org.ironrhino.core.security.verfication.VerificationManager;
import org.ironrhino.core.spring.security.CredentialsNeedResetException;
import org.ironrhino.core.spring.security.DefaultUsernamePasswordAuthenticationFilter;
import org.ironrhino.core.struts.BaseAction;
import org.ironrhino.core.util.AuthzUtils;
import org.ironrhino.core.util.ExceptionUtils;
import org.ironrhino.security.oauth.server.component.OAuthErrorHandler;
import org.ironrhino.security.oauth.server.domain.OAuthError;
import org.ironrhino.security.oauth.server.enums.GrantType;
import org.ironrhino.security.oauth.server.enums.ResponseType;
import org.ironrhino.security.oauth.server.event.AuthorizeEvent;
import org.ironrhino.security.oauth.server.model.Authorization;
import org.ironrhino.security.oauth.server.model.Client;
import org.ironrhino.security.oauth.server.service.OAuthManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@AutoConfig
@Slf4j
public class Oauth2Action extends BaseAction {

	private static final long serialVersionUID = 8175470892708878896L;

	@Autowired
	protected EventPublisher eventPublisher;

	@Autowired
	private OAuthManager oauthManager;

	@Autowired
	private OAuthErrorHandler oauthErrorHandler;

	@Autowired
	private UserDetailsService userDetailsService;

	@Autowired
	private DefaultUsernamePasswordAuthenticationFilter usernamePasswordAuthenticationFilter;

	@Autowired
	protected AuthenticationFailureHandler authenticationFailureHandler;

	@Autowired
	protected AuthenticationSuccessHandler authenticationSuccessHandler;

	@Autowired
	private AuthenticationManager authenticationManager;

	@Autowired(required = false)
	protected VerificationManager verificationManager;

	@Getter
	@Setter
	private String username;
	@Getter
	@Setter
	private String password;
	@Getter
	@Setter
	private String client_id;
	@Getter
	@Setter
	private String client_secret;
	@Getter
	@Setter
	private String device_id;
	@Getter
	@Setter
	private String device_name;
	@Getter
	@Setter
	private String redirect_uri;
	@Getter
	@Setter
	private String scope;
	@Getter
	@Setter
	private String code;
	@Getter
	@Setter
	private ResponseType response_type;
	@Getter
	@Setter
	private GrantType grant_type;
	@Getter
	@Setter
	private String state;
	@Getter
	@Setter
	private String access_token;
	@Getter
	@Setter
	private String refresh_token;
	@Getter
	@Setter
	private String token;
	@Getter
	@Setter
	private String approval_prompt;

	@Getter
	private Authorization authorization;
	@Getter
	private Client client;

	@Getter
	private Map<String, Serializable> tojson;
	@Getter
	private boolean displayForNative;
	@Getter
	private boolean granted;
	@Getter
	private boolean denied;

	@Override
	public String execute() {
		return SUCCESS;
	}

	public String auth() throws Exception {
		client = oauthManager.findClientById(client_id);
		if (client == null)
			throw new IllegalArgumentException("client_id_invalid");
		UserDetails grantor = AuthzUtils.getUserDetails();
		if (!"force".equals(approval_prompt) && grantor != null) {
			List<Authorization> auths = oauthManager.findAuthorizationsByGrantor(grantor.getUsername());
			for (Authorization auth : auths) {
				if (Objects.equals(auth.getClient(), client.getId())
						&& Objects.equals(auth.getResponseType(), response_type)
						&& Objects.equals(auth.getScope(), scope)) {
					authorization = auth;
					break;
				}
			}
			if (authorization != null) {
				authorization = oauthManager.reuse(authorization);
				return grant();
			}
		}
		authorization = oauthManager.generate(client, redirect_uri, scope, response_type);
		client = oauthManager.findClientById(authorization.getClient());
		displayForNative = client.isNative();
		setUid(authorization.getId());
		return INPUT;
	}

	public String grant() throws Exception {
		HttpServletRequest request = ServletActionContext.getRequest();
		HttpServletResponse response = ServletActionContext.getResponse();
		UserDetails grantor = AuthzUtils.getUserDetails();
		if (grantor == null) {
			try {
				Authentication authResult = authenticationManager
						.authenticate(new UsernamePasswordAuthenticationToken(username, password));
				if (authResult != null) {
					usernamePasswordAuthenticationFilter.success(request, response, authResult);
					grantor = (UserDetails) authResult.getPrincipal();
				}
			} catch (UsernameNotFoundException | DisabledException | LockedException | AccountExpiredException failed) {
				addFieldError("username", getText(failed.getClass().getName()));
				return INPUT;
			} catch (BadCredentialsException | CredentialsExpiredException | CredentialsNeedResetException failed) {
				usernamePasswordAuthenticationFilter.unsuccess(request, response, failed);
				addFieldError("password", getText(failed.getClass().getName()));
				captchaManager.addCaptchaCount(request);
				return INPUT;
			} catch (InternalAuthenticationServiceException failed) {
				log.error(failed.getMessage(), failed);
				addActionError(ExceptionUtils.getRootMessage(failed));
				return INPUT;
			}
		}
		if (authorization == null)
			authorization = oauthManager.grant(getUid(), grantor.getUsername());
		client = oauthManager.findClientById(authorization.getClient());
		displayForNative = client.isNative();
		granted = true;
		if (displayForNative) {
			return INPUT;
		} else {
			StringBuilder sb = new StringBuilder(redirect_uri);
			if (authorization.isClientSide()) {
				sb.append("#");
				sb.append("access_token=").append(authorization.getAccessToken());
				sb.append("&expires_in=").append(authorization.getExpiresIn());
			} else {
				sb.append(sb.indexOf("?") > 0 ? "&" : "?").append("code=").append(authorization.getCode());
			}
			if (StringUtils.isNotBlank(state))
				try {
					sb.append("&state=").append(URLEncoder.encode(state, "UTF-8"));
				} catch (UnsupportedEncodingException e) {
				}
			targetUrl = sb.toString();
		}
		return REDIRECT;
	}

	public String deny() {
		oauthManager.deny(getUid());
		denied = true;
		if (Client.OAUTH_OOB.equals(redirect_uri)) {
			displayForNative = true;
			return INPUT;
		}
		StringBuilder sb = new StringBuilder(redirect_uri);
		sb.append(sb.indexOf("?") > 0 ? "&" : "?").append("error=access_denied");
		if (StringUtils.isNotBlank(state))
			try {
				sb.append("&state=").append(URLEncoder.encode(state, "UTF-8"));
			} catch (UnsupportedEncodingException e) {
			}
		targetUrl = sb.toString();
		return REDIRECT;
	}

	@JsonConfig(root = "tojson")
	public String token() throws IOException {
		HttpServletRequest request = ServletActionContext.getRequest();
		HttpServletResponse response = ServletActionContext.getResponse();
		response.setHeader("Cache-Control", "no-store");
		response.setHeader("Pragma", "no-store");
		if (grant_type == GrantType.password) {
			client = oauthManager.findClientById(client_id);
			try {
				if (client == null) {
					oauthErrorHandler.handle(request, response,
							new OAuthError(OAuthError.INVALID_CLIENT, "client_id_not_exists"));
					return NONE;
				}
				if (!client.getSecret().equals(client_secret)) {
					oauthErrorHandler.handle(request, response,
							new OAuthError(OAuthError.INVALID_CLIENT, "client_secret_mismatch"));
					return NONE;
				}
				try {
					Authentication authResult = authenticationManager
							.authenticate(new UsernamePasswordAuthenticationToken(username, password));
					if (authResult != null)
						authenticationSuccessHandler.onAuthenticationSuccess(request, response, authResult);
				} catch (InternalAuthenticationServiceException failed) {
					throw new IllegalArgumentException(ExceptionUtils.getRootMessage(failed));
				}
				// catch (BadVerificationCodeException e) {
				// throw new IllegalArgumentException("INVALID_VERIFICATION_CODE");
				// } catch (UsernameNotFoundException e) {
				// throw new IllegalArgumentException("USERNAME_NOT_EXISTS");
				// } catch (BadCredentialsException e) {
				// throw new IllegalArgumentException("BAD_CREDENTIALS");
				// }
				catch (AuthenticationException failed) {
					authenticationFailureHandler.onAuthenticationFailure(request, response, failed);
					throw new IllegalArgumentException(getText(failed.getClass().getName()));
				}
				UserDetails u = userDetailsService.loadUserByUsername(username);
				authorization = oauthManager.grant(client, u.getUsername(), device_id, device_name);
			} catch (Exception e) {
				log.error("Exchange token by password for \"{}\" failed with {}: {}", username, e.getClass().getName(),
						e.getLocalizedMessage());
				oauthErrorHandler.handle(request, response, e instanceof OAuthError ? (OAuthError) e
						: new OAuthError(OAuthError.INVALID_REQUEST, e.getLocalizedMessage()));
				return NONE;
			}
			tojson = new LinkedHashMap<>();
			tojson.put("access_token", authorization.getAccessToken());
			tojson.put("refresh_token", authorization.getRefreshToken());
			tojson.put("expires_in", authorization.getExpiresIn());
			eventPublisher.publish(new AuthorizeEvent(authorization.getGrantor(), request.getRemoteAddr(),
					client.getName(), grant_type.name()), Scope.LOCAL);
			return JSON;
		} else if (grant_type == GrantType.client_credential || grant_type == GrantType.client_credentials) {
			client = new Client();
			client.setId(client_id);
			client.setSecret(client_secret);
			try {
				authorization = oauthManager.grant(client, device_id, device_name);
			} catch (Exception e) {
				log.error("Exchange token by client_credentials for \"{}\" failed with {}: {}", client_id,
						e.getClass().getName(), e.getLocalizedMessage());
				oauthErrorHandler.handle(request, response, e instanceof OAuthError ? (OAuthError) e
						: new OAuthError(OAuthError.INVALID_REQUEST, e.getLocalizedMessage()));
				return NONE;
			}
			tojson = new LinkedHashMap<>();
			tojson.put("access_token", authorization.getAccessToken());
			tojson.put("refresh_token", authorization.getRefreshToken());
			tojson.put("expires_in", authorization.getExpiresIn());
			return JSON;
		} else if (grant_type == GrantType.refresh_token) {
			client = new Client();
			client.setId(client_id);
			client.setSecret(client_secret);
			try {
				authorization = oauthManager.refresh(client, refresh_token);
				tojson = new LinkedHashMap<>();
				tojson.put("access_token", authorization.getAccessToken());
				tojson.put("expires_in", authorization.getExpiresIn());
				tojson.put("refresh_token", authorization.getRefreshToken());
			} catch (Exception e) {
				log.error("Refresh token \"{}\" failed with {}: {}", refresh_token, e.getClass().getName(),
						e.getLocalizedMessage());
				oauthErrorHandler.handle(request, response, e instanceof OAuthError ? (OAuthError) e
						: new OAuthError(OAuthError.INVALID_REQUEST, e.getLocalizedMessage()));
				return NONE;
			}
			return JSON;
		} else {
			if (grant_type != GrantType.authorization_code) {
				String message = "grant_type must be authorization_code";
				oauthErrorHandler.handle(request, response, new OAuthError(OAuthError.UNSUPPORTED_GRANT_TYPE, message));
				return NONE;
			}
			client = new Client();
			client.setId(client_id);
			client.setSecret(client_secret);
			client.setRedirectUri(redirect_uri);
			try {
				authorization = oauthManager.authenticate(code, client);
				tojson = new LinkedHashMap<>();
				tojson.put("access_token", authorization.getAccessToken());
				tojson.put("expires_in", authorization.getExpiresIn());
				tojson.put("refresh_token", authorization.getRefreshToken());
				eventPublisher.publish(new AuthorizeEvent(authorization.getGrantor(), request.getRemoteAddr(),
						client.getName(), grant_type.name()), Scope.LOCAL);
			} catch (Exception e) {
				log.error("Exchange token by code for \"{}\" failed with {}: {}", code, e.getClass().getName(),
						e.getLocalizedMessage());
				oauthErrorHandler.handle(request, response, e instanceof OAuthError ? (OAuthError) e
						: new OAuthError(OAuthError.INVALID_REQUEST, e.getLocalizedMessage()));
				return NONE;
			}
			return JSON;
		}
	}

	@JsonConfig(root = "tojson")
	public String info() throws IOException {
		HttpServletRequest request = ServletActionContext.getRequest();
		HttpServletResponse response = ServletActionContext.getResponse();
		if (access_token == null && token != null)
			access_token = token;
		tojson = new LinkedHashMap<>();
		authorization = oauthManager.retrieve(access_token);
		if (authorization == null) {
			oauthErrorHandler.handle(request, response, new OAuthError(OAuthError.INVALID_TOKEN));
			return NONE;
		} else if (authorization.getExpiresIn() < 0) {
			oauthErrorHandler.handle(request, response, new OAuthError(OAuthError.INVALID_TOKEN, "expired_token"));
			return NONE;
		} else {
			if (authorization.getClient() != null)
				tojson.put("client_id", authorization.getClient());
			if (authorization.getGrantor() != null)
				tojson.put("username", authorization.getGrantor());
			tojson.put("expires_in", authorization.getExpiresIn());
			if (authorization.getScope() != null)
				tojson.put("scope", authorization.getScope());
		}
		return JSON;
	}

	public String revoke() throws IOException {
		if (access_token == null && token != null)
			access_token = token;
		boolean revoked = oauthManager.revoke(access_token);
		if (!revoked) {
			HttpServletRequest request = ServletActionContext.getRequest();
			HttpServletResponse response = ServletActionContext.getResponse();
			oauthErrorHandler.handle(request, response, new OAuthError(OAuthError.INVALID_REQUEST, "revoke_failed"));
		}
		return NONE;
	}

	@JsonConfig(root = "tojson")
	public String sendVerificationCode() throws IOException {
		if (verificationManager == null) {
			tojson = new LinkedHashMap<>();
			tojson.put("code", "2");
			tojson.put("status", "FORBIDDEN");
			return NONE;
		}
		HttpServletRequest request = ServletActionContext.getRequest();
		HttpServletResponse response = ServletActionContext.getResponse();
		try {
			client = oauthManager.findClientById(client_id);
			if (client == null)
				throw new IllegalArgumentException("client_id_not_exists");
			if (!client.getSecret().equals(client_secret))
				throw new IllegalArgumentException("client_secret_mismatch");
			if (verificationManager != null && StringUtils.isNotBlank(username)) {
				try {
					verificationManager.send(username);
					tojson = new LinkedHashMap<>();
					tojson.put("code", "0");
					tojson.put("status", "OK");
				} catch (AuthenticationException failed) {
					throw new IllegalArgumentException(getText(failed.getClass().getName()));
				}
			}
		} catch (Exception e) {
			log.error("Send verification code to \"{}\" failed with {}: {}", username, e.getClass().getName(),
					e.getLocalizedMessage());
			oauthErrorHandler.handle(request, response, e instanceof OAuthError ? (OAuthError) e
					: new OAuthError(OAuthError.INVALID_REQUEST, e.getLocalizedMessage()));
			return NONE;
		}
		return JSON;
	}

}
