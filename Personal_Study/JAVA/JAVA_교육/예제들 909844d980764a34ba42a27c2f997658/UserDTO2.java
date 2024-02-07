package com.greedy.section04.constructor.dto;

import java.util.Date;

public class UserDTO2 {

	/* 단축키를 활용해서 자바빈(java bean)클래스를 좀 더 쉽고 빠르게 만들자 */
	
	/* 필드 변수 */
	private String id;
	private String pwd;
	private String name;
	private java.util.Date enrollDate;

	/* 생성자(alt + shift + s -> o) */
	public UserDTO2() {
	}
	public UserDTO2(String id, String pwd, String name, Date enrollDate) {
		this.id = id;
		this.pwd = pwd;
		this.name = name;
		this.enrollDate = enrollDate;
	}

	/* setter, getter(alt + shift + s -> r */
	public String getId() {
		return id;
	}
	public void setId(String id) {
		this.id = id;
	}
	public String getPwd() {
		return pwd;
	}
	public void setPwd(String pwd) {
		this.pwd = pwd;
	}
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public java.util.Date getEnrollDate() {
		return enrollDate;
	}
	public void setEnrollDate(java.util.Date enrollDate) {
		this.enrollDate = enrollDate;
	}
	
	/* toString() (alt + shift + s ->s*/
	
	@Override
	public String toString() {
		return "UserDTO2 [id=" + id + ", pwd=" + pwd + ", name=" + name + ", enrollDate=" + enrollDate + "]";
	}

	
	
}
	
	
