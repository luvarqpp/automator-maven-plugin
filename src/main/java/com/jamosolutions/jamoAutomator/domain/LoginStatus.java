package com.jamosolutions.jamoAutomator.domain;

import java.io.Serializable;

public class LoginStatus implements Serializable {
	private boolean success;
	private String username;
	private String firstName;
	private String userKeyString;
	private Boolean isSuperAdmin;
	private Boolean isAdmin;
	private Boolean isEditor;
	private Boolean isActivated;
	private String account;

	public LoginStatus() {
	}

	public LoginStatus(boolean success, String username) {
		this.success = success;
		this.username = username;
		this.isSuperAdmin = false;
		this.isAdmin = false;
		this.isEditor = false;
		this.isActivated = false;
		this.userKeyString = null;
	}

	public LoginStatus(boolean success, String username, Boolean isSuperAdmin, Boolean isAdmin, Boolean isEditor, Boolean isActivated, String userKeyString,
			String firstName) {
		this.success = success;
		this.username = username;
		this.isSuperAdmin = isSuperAdmin;
		this.isAdmin = isAdmin;
		this.isEditor = isEditor;
		this.isActivated = isActivated;
		this.userKeyString = userKeyString;
		this.firstName = firstName;
	}

	public boolean isSuccess() {
		return success;
	}

	public String getUsername() {
		return username;
	}

	public Boolean isAdmin() {
		return isAdmin;
	}

	public Boolean isEditor() {
		return isEditor;
	}

	public Boolean isSuperAdmin() {
		return isSuperAdmin;
	}

	public Boolean isActivated() {
		return isActivated;
	}

	public String getUserKeyString() {
		return userKeyString;
	}

	public String getAccount() {
		return account;
	}

	public void setAccount(String account) {
		this.account = account;
	}

	public String getFirstName() {
		return firstName;
	}

	public void setFirstName(String firstName) {
		this.firstName = firstName;
	}

	@Override
	public String toString() {
		return "LoginStatus(success: " + success + ", username: " + username + ", firstName: " + firstName + ", userKeyString: " + userKeyString + ", isSuperAdmin: " + isSuperAdmin + ", isAdmin: " + isAdmin + ", isEditor: " + isEditor + ", isActivated: " + isActivated + ", account: " + account + ")";
	}
}
