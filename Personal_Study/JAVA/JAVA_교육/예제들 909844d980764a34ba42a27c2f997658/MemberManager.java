package com.greedy.section02.uses;

public class MemberManager {

	public void signUpMembers() {
		
		/* 5명의 회원정보를 담을 객체 배열 생성 */
		MemberDTO[] members = new MemberDTO[5];
		
		/* 5명의 회원 정보를 각 인덱스에 할당 */
		members[0] = new MemberDTO(1, "user01", "pass01", "홍길동", 20, '남');
		members[1] = new MemberDTO(2, "user02", "pass02", "유관순", 16, '여');
		members[2] = new MemberDTO(3, "user03", "pass03", "이순신", 40, '남');
		members[3] = new MemberDTO(4, "user04", "pass04", "신사임당", 36, '여');
		members[4] = new MemberDTO(5, "user05", "pass05", "윤봉길", 22, '남');
		
		MemberInsertManager mi = new MemberInsertManager();
		mi.insert(members);
	}

	public void printAllmembers() {
		MemberDTO[] selectedMembers = new MemberSelectmanager().selectAllMembers();
		
		System.out.println("-------- 가입된 회원 목록 --------");
		
		for(MemberDTO member : selectedMembers) {
			System.out.println(member);
		}
		System.out.println("----------------");
		System.out.println("총 " + selectedMembers.length + "명이 가입되어 있습니다.");
	}

}
