package com.greedy.section02.uses;

public class MemberInsertManager {

	public void insert(MemberDTO[] members) {

		System.out.println("회원을 등록합니다.");
		
		for(MemberDTO member : members) {
			System.out.println(member.getName() + "님을 회원 등록에 성공하였습니다.");
		}
		
		System.out.println("총 " + members.length + "명의 회원 등록에 성공하였습니다.");
	}

}
